package org.jenkinsci.plugins.lucene.search.databackend;

import static org.jenkinsci.plugins.lucene.search.Field.*;

import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryTermScorer;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.jenkinsci.plugins.lucene.search.Field;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchExtension;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;

public class LuceneSearchBackend extends SearchBackend<Document> {
    private static final Logger LOG = Logger.getLogger(LuceneSearchBackend.class.getName());

    private static final int MAX_NUM_FRAGMENTS = 5;
    private static final String[] EMPTY_ARRAY = new String[0];
    private static final Locale LOCALE = Locale.ENGLISH;
    private static final Pattern TERM_PATTERN = Pattern.compile("(?<field>\\S+:)?(?<text>[^\\\"]\\S*|\\\".+?\\\")\\s*");

    private static final org.apache.lucene.document.Field.Store DONT_STORE = org.apache.lucene.document.Field.Store.NO;
    private static final org.apache.lucene.document.Field.Store STORE = org.apache.lucene.document.Field.Store.YES;

    private enum LuceneFieldType {
        STRING,
        LONG,
        TEXT
    }

    private boolean isConsoleField(Field field) {
        return field == Field.CONSOLE;
    }

    static final Map<Field, LuceneFieldType> FIELD_TYPE_MAP;

    static {
        Map<Field, LuceneFieldType> types = new HashMap<>();
        types.put(PROJECT_NAME, LuceneFieldType.TEXT);
        types.put(BUILD_NUMBER, LuceneFieldType.STRING);
        types.put(CONSOLE, LuceneFieldType.TEXT);
        types.put(BUILD_DISPLAY_NAME, LuceneFieldType.TEXT);
        types.put(BUILD_PARAMETER, LuceneFieldType.TEXT);
        FIELD_TYPE_MAP = Collections.unmodifiableMap(types);
    }

    private static final Comparator<String> BUILD_COMPARATOR = new Comparator<String>() {
        @Override
        public int compare(String o1, String o2) {
            if (o2 == null) {
                return 1;
            }
            return o2.compareTo(o1);
        }
    };

    private static final int MAX_HITS_PER_PAGE = 100;

    /**
     * A synthetic stored field that uniquely identifies a build document.
     * Used with {@link IndexWriter#updateDocument(Term, Iterable)} to make
     * storeBuild an atomic upsert — no gap between remove and add.
     */
    static final String UNIQUE_KEY_FIELD = "_unique_key";

    private final Analyzer analyzer;
    private Directory index;
    private IndexWriter dbWriter;
    private SearcherManager searcherManager;
    private final Jenkins jenkins;
    private volatile ScoreDoc lastDoc;
    private final boolean collectBuildLogs;

    /** Builds the unique key for a Run: "projectFullName#buildNumber". */
    private static String uniqueKeyFor(Run<?, ?> run) {
        return run.getParent().getFullName() + "#" + run.getNumber();
    }

    /** Builds the unique {@link Term} for a Run, used for upsert/deletion. */
    static Term uniqueTermFor(Run<?, ?> run) {
        return new Term(UNIQUE_KEY_FIELD, uniqueKeyFor(run));
    }

    public LuceneSearchBackend(final File indexPath, final boolean useBuildLogs) throws IOException {
        analyzer = new CaseSensitiveAnalyzer();
        index = FSDirectory.open(indexPath.toPath());
        collectBuildLogs = useBuildLogs;
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try {
            dbWriter = new IndexWriter(index, config);
        } catch (LockObtainFailedException e) {
            // Never force-unlock here. This exception can only be thrown while a
            // *live* holder exists: after an unclean shutdown or a restart the
            // operating system has already released the lock, so a write.lock
            // file left on disk blocks nothing on its own. Deleting the file
            // never releases the holder's lock either, and it is destructive in
            // both cases it can hit:
            //  - same classloader: Lucene still refuses the second writer (its
            //    LOCK_HELD set), but the live writer's lock file has already
            //    been deleted, so that writer fails its next lock validation
            //    with "Underlying file changed by an external force" and dies.
            //  - after a plugin reload, where the new classloader knows nothing
            //    about the old writer, the second writer is created and two
            //    writers share one index directory.
            throw new IOException(lockConflictMessage(indexPath, e), e);
        } catch (IllegalArgumentException e) {
            // The existing index may use an incompatible codec (e.g., Lucene87 after
            // upgrading from 8.x) or may be corrupt. Delete it and start fresh.
            LOG.severe("Lucene index at " + indexPath + " cannot be opened ("
                    + e.getMessage() + "); deleting and recreating it. Search data is lost until you run"
                    + " 'Manage Jenkins -> Lucene Search Manager -> Rebuild'.");
            IOUtils.closeQuietly(index);
            deleteDirectory(indexPath);
            index = FSDirectory.open(indexPath.toPath());
            dbWriter = new IndexWriter(index, config);
        }
        dbWriter.commit();
        searcherManager = new SearcherManager(dbWriter, null);
        jenkins = Jenkins.get();
    }

    /**
     * Lucene reports the two possible holders in its message: a lock held by a
     * live writer in this JVM ("this virtual machine") and one held by another
     * process ("another program"). The first is the case this plugin can actually
     * produce — two writers on one index in a single Jenkins JVM — and is worth
     * saying plainly; the second is kept only as a defensive branch. The wording
     * check is guarded so a future Lucene change cannot break it.
     */
    private static String lockConflictMessage(File indexPath, LockObtainFailedException cause) {
        String luceneMessage = cause.getMessage() == null ? "" : cause.getMessage();
        if (luceneMessage.contains("this virtual machine")) {
            return "Lucene index " + indexPath + " is locked by another search backend in this Jenkins JVM:"
                    + " two writers on one index. Refusing to steal the lock; this backend stays unavailable"
                    + " until that writer is closed.";
        }
        return "Lucene index " + indexPath + " is locked by a process outside this JVM (not expected on a"
                + " single-instance host — check it with: fuser -v " + indexPath + "/write.lock). Refusing to"
                + " steal the lock.";
    }

    /**
     * Deletes an index directory that could not be opened. Refuses to touch a
     * symlink or a directory without a segments file, so a misconfigured
     * {@code lucenePath} can never wipe data that is not a Lucene index.
     */
    private static void deleteDirectory(File directory) throws IOException {
        Path path = directory.toPath();
        if (!Files.exists(path)) {
            return;
        }
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Refusing to delete symlinked index path: " + path);
        }
        try (Stream<Path> entries = Files.list(path)) {
            if (entries.noneMatch(entry -> entry.getFileName().toString().startsWith("segments_"))) {
                throw new IOException("Refusing to delete " + path + ": it has no segments_ file, so it does not"
                        + " look like a Lucene index.");
            }
        }
        org.apache.commons.io.FileUtils.deleteDirectory(directory);
    }

    public static LuceneSearchBackend create(final Map<String, Object> config) {
        try {
            boolean shouldCollect = false;
            if (config.containsKey("collectBuildLogs")) {
                shouldCollect = (boolean) config.get("collectBuildLogs");
            }
            return new LuceneSearchBackend(getIndexPath(config), shouldCollect);
        } catch (IOException e) {
            LOG.log(Level.SEVERE,"create lucene search backend failed: " + e);
        }
        return null;
    }

    private static File getIndexPath(final Map<String, Object> config) {
        return (File) config.get("lucenePath");
    }

    @Override
    public SearchBackend<Document> reconfigure(final Map<String, Object> newConfig) {
        close();
        return create(newConfig);
    }

    public void close() {
        IOUtils.closeQuietly(searcherManager);
        IOUtils.closeQuietly(dbWriter);
        IOUtils.closeQuietly(index);
    }

    private Long getWithDefault(String number, Long defaultNumber) {
        if (number != null) {
            try {
                return Long.parseLong(number);
            } catch (NumberFormatException e) {
                // fall through to default
            }
        }
        return defaultNumber;
    }

    private static Set<String> calculateQueryFieldsRecursively(Query query) {
        Set<String> fields = new HashSet<>();

        if (query instanceof TermQuery) {
            TermQuery tQuery = (TermQuery) query;
            Term term = tQuery.getTerm();
            fields.add(term.field());
        } else if (query instanceof BooleanQuery) {
            BooleanQuery bQuery = (BooleanQuery) query;
            List<BooleanClause> clauses = bQuery.clauses();
            for (BooleanClause clause : clauses) {
                Query innerQuery = clause.getQuery();
                Set<String> innerFields = calculateQueryFieldsRecursively(innerQuery);
                fields.addAll(innerFields);
            }
        } else if (query instanceof PhraseQuery) {
            PhraseQuery pQuery = (PhraseQuery) query;
            for (Term term : pQuery.getTerms()) {
                fields.add(term.field());
            }
        } else if (query instanceof WildcardQuery) {
            WildcardQuery wQuery = (WildcardQuery) query;
            Term term = wQuery.getTerm();
            fields.add(term.field());
        }
        return fields;
    }

    private Pair<Query, Query, Boolean> parseQuery(String q, IndexSearcher searcher)
            throws ParseException, IOException {

        List<String> words = new ArrayList<>(Arrays.asList(q.trim().split("\\s+", 2)));
        words.removeAll(Arrays.asList("", null));

        QueryParser parser = getQueryParser();
        Query query = parser.parse(escapeQuery(q));
        Query highlight = query;

        if (words.size() >= 2) {
            try {
                Query jobNameQuery = parser.parse(PROJECT_NAME.fieldName + ":" + QueryParser.escape(words.get(0)));
                if (searcher.search(jobNameQuery, 1).scoreDocs.length > 0) {
                    highlight = parser.parse(escapeQuery(words.get(1)));
                    query = new BooleanQuery.Builder()
                            .add(jobNameQuery, BooleanClause.Occur.MUST)
                            .add(highlight, BooleanClause.Occur.MUST)
                            .build();
                }
            } catch (ParseException e) {
                // proceed with multi-job search
            }
        }

        Set<String> fields = calculateQueryFieldsRecursively(highlight);
        return new Pair<>(searcher.rewrite(query), searcher.rewrite(highlight), fields.contains(CONSOLE.fieldName));
    }

    @SuppressWarnings("rawtypes")
    @Override
    public List<FreeTextSearchItemImplementation> getHits(String q, boolean searchNext) {
        List<FreeTextSearchItemImplementation> luceneSearchResultImpl = new ArrayList<>();
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            Pair<Query, Query, Boolean> fieldQueryPair = parseQuery(q, searcher);
            Query query = fieldQueryPair.first;
            Query highlight = fieldQueryPair.second;
            Boolean isShowConsole = fieldQueryPair.third;

            QueryTermScorer scorer = new QueryTermScorer(highlight);
            Highlighter highlighter = new Highlighter(new SimpleHTMLFormatter(), scorer);
            highlighter.setMaxDocCharsToAnalyze(Integer.MAX_VALUE);
            ScoreDoc[] hits;
            if (searchNext) {
                hits = searcher.searchAfter(lastDoc, query, MAX_HITS_PER_PAGE).scoreDocs;
            } else {
                hits = searcher.searchAfter(null, query, MAX_HITS_PER_PAGE).scoreDocs;
            }
            if (hits.length != 0) {
                lastDoc = hits[hits.length - 1];
            }
            TreeMap<String, Document> docs = new TreeMap<>(BUILD_COMPARATOR);

            for (ScoreDoc hit : hits) {
                Document doc = searcher.doc(hit.doc);
                docs.put(doc.get(PROJECT_NAME.fieldName) + doc.get(BUILD_DISPLAY_NAME.fieldName), doc);
            }

            for (Document doc : docs.values()) {
                String[] bestFragments = EMPTY_ARRAY;
                try {
                    bestFragments = highlighter.getBestFragments(
                            analyzer, CONSOLE.fieldName, doc.get(CONSOLE.fieldName), MAX_NUM_FRAGMENTS);
                } catch (InvalidTokenOffsetsException e) {
                    LOG.log(Level.FINE, "Failed to find bestFragments", e);
                }

                String projectName = doc.get(PROJECT_NAME.fieldName);
                String buildNumber = doc.get(BUILD_NUMBER.fieldName);
                String searchName = doc.get(PROJECT_NAME.fieldName) + doc.get(BUILD_DISPLAY_NAME.fieldName);

                Item jobItem = jenkins.getItemByFullName(projectName);
                if (jobItem == null) {
                    LOG.fine("Project not found (removed or renamed): " + projectName);
                    continue;
                }
                if (!(jobItem instanceof Job)) {
                    LOG.fine("Unknown project type for project name: " + projectName);
                    continue;
                }
                Job job = (Job) jobItem;
                Run build = job.getBuildByNumber(Integer.parseInt(buildNumber));
                if (build == null) {
                    LOG.fine(
                            "Build #" + buildNumber + " not found for project " + projectName + " (possibly removed)");
                    continue;
                }
                FreeTextSearchItemImplementation itemImpl = new FreeTextSearchItemImplementation(
                        searchName, projectName, bestFragments, build.getUrl(), isShowConsole);
                luceneSearchResultImpl.add(itemImpl);
            }
        } catch (ParseException e) {
            //            LOG.warning("Search Parsing Error: ", e);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Search IO Error: ", e);
        } catch (AlreadyClosedException e) {
            LOG.log(Level.WARNING, "IndexReader is closed: ", e);
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to release searcher: ", e);
                }
            }
        }
        return luceneSearchResultImpl;
    }

    private MultiFieldQueryParser getQueryParser() {
        MultiFieldQueryParser queryParser = new MultiFieldQueryParser(getAllDefaultSearchableFields(), analyzer) {
            @Override
            protected Query getRangeQuery(
                    String field, String part1, String part2, boolean startInclusive, boolean endInclusive)
                    throws ParseException {
                if (field != null && getIndex(field).numeric) {
                    Long min = getWithDefault(part1, null);
                    Long max = getWithDefault(part2, null);
                    return LongPoint.newRangeQuery(field, min, max);
                } else if (field != null) {
                    return new TermQuery(new Term(field));
                }
                return super.getRangeQuery(null, part1, part2, startInclusive, endInclusive);
            }
        };
        queryParser.setDefaultOperator(QueryParser.Operator.AND);
        queryParser.setLocale(LOCALE);
        queryParser.setAllowLeadingWildcard(true);
        queryParser.setMultiTermRewriteMethod(MultiTermQuery.CONSTANT_SCORE_BOOLEAN_REWRITE);
        return queryParser;
    }

    @Override
    public void storeBuild(final Run<?, ?> run) throws IOException {
        LOG.fine("LuceneBackend.storeBuild: build=" + run.getFullDisplayName()
                + " number=" + run.getNumber()
                + " project=" + run.getParent().getFullName());
        Document doc = new Document();
        // Unique key for atomic upsert — updateDocument deletes any
        // existing doc with the same term before adding this one.
        doc.add(new StringField(UNIQUE_KEY_FIELD, uniqueKeyFor(run), STORE));
        for (Field field : Field.values()) {
            org.apache.lucene.document.Field.Store store = field.persist ? STORE : DONT_STORE;
            if (isConsoleField(field) && !collectBuildLogs) {
                LOG.fine("Skipping console log indexing for field: " + field.fieldName);
                doc.add(new TextField(field.fieldName, "", store));
            } else {
                Object fieldValue = field.getValue(run);
                if (fieldValue != null) {
                    switch (FIELD_TYPE_MAP.get(field)) {
                        case LONG:
                            doc.add(new LongPoint(field.fieldName, ((Number) fieldValue).longValue()));
                            break;
                        case STRING:
                            doc.add(new StringField(field.fieldName, fieldValue.toString(), store));
                            break;
                        case TEXT:
                            doc.add(new TextField(field.fieldName, fieldValue.toString(), store));
                            break;
                        default:
                            throw new IllegalArgumentException(
                                    "Don't know how to handle " + FIELD_TYPE_MAP.get(field));
                    }
                }
            }
        }

        for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {
            try {
                Object fieldValue = extension.getTextResult(run);
                if (fieldValue != null) {
                    doc.add(new TextField(
                            extension.getKeyword(),
                            extension.getTextResult(run),
                            (extension.isPersist()) ? STORE : DONT_STORE));
                }
            } catch (Throwable t) {
                // We don't want to crash the collection of log from other plugin extensions if we happen
                // to add a plugin that crashes while collecting the logs.
                LOG.warning("CRASH: " + extension.getClass().getName() + ", " + extension.getKeyword() + t);
            }
        }
        // Atomic upsert: delete any existing doc for this build, then add.
        // No gap between remove and add — the build never disappears from searches.
        dbWriter.updateDocument(uniqueTermFor(run), doc);
        LOG.fine("LuceneBackend.storeBuild: document upserted (updateDocument) for build="
                + run.getFullDisplayName() + " (commitWrites will flush)");
    }

    public Query getRunQuery(Run<?, ?> run) throws ParseException {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        String[] parts = run.getParent().getFullName().split("/");
        PhraseQuery.Builder phraseBuilder = new PhraseQuery.Builder();
        for (int i = 0; i < parts.length; i++) {
            phraseBuilder.add(new Term(PROJECT_NAME.fieldName, parts[i]), i);
        }
        builder.add(phraseBuilder.build(), BooleanClause.Occur.MUST)
                .add(getQueryParser().parse(BUILD_NUMBER.fieldName + ":" + run.getNumber()), BooleanClause.Occur.MUST);
        return builder.build();
    }

    @Override
    public boolean findRunIndex(Run<?, ?> run) {
        IndexSearcher searcher = null;
        try {
            Term term = uniqueTermFor(run);
            Query query = new TermQuery(term);
            searcher = searcherManager.acquire();
            TopDocs docs = searcher.search(query, 1);
            return docs.scoreDocs.length > 0;
        } catch (IOException e) {
            LOG.warning("findRunIndex: " + e);
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to release searcher in findRunIndex: ", e);
                }
            }
        }
        return false;
    }

    @Override
    public void removeBuild(Run<?, ?> run) throws IOException {
        LOG.fine("LuceneBackend.removeBuild: build=" + run.getFullDisplayName()
                + " number=" + run.getNumber()
                + " project=" + run.getParent().getFullName());
        Term term = uniqueTermFor(run);
        LOG.fine("LuceneBackend.removeBuild: term=" + term);
        dbWriter.deleteDocuments(term);
    }

    @Override
    public void deleteJob(String jobName) throws IOException {
        LOG.fine("LuceneBackend.deleteJob: job=" + jobName);
        try {
            String[] parts = jobName.split("/");
            PhraseQuery.Builder phraseBuilder = new PhraseQuery.Builder();
            for (int i = 0; i < parts.length; i++) {
                phraseBuilder.add(new Term(PROJECT_NAME.fieldName, parts[i]), i);
            }
            dbWriter.deleteDocuments(phraseBuilder.build());
        } catch (IOException e) {
            LOG.log(Level.SEVERE,"Could not delete job", e);
        }
    }

    @Override
    public void commitWrites() throws IOException {
        LOG.fine("LuceneBackend.commitWrites: committing and refreshing searcher");
        dbWriter.commit();
        searcherManager.maybeRefresh();
        LOG.fine("LuceneBackend.commitWrites: done");
    }

    @Override
    public void cleanAllJob(ManagerProgress progress) {
        Progress currentProgress = progress.beginCleanJob();
        IndexSearcher searcher = null;
        try {
            searcher = searcherManager.acquire();
            currentProgress.setCurrent(searcher.getIndexReader().numDocs());
            dbWriter.deleteAll();
            dbWriter.commit();
            searcherManager.maybeRefresh();
            progress.setSuccessfullyCompleted();
        } catch (IOException e) {
            progress.completedWithErrors(e);
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Failed to release searcher in cleanAllJob: ", e);
                }
            }
            currentProgress.setFinished();
            progress.jobComplete();
        }
    }

    public static String escapeQuery(String q) {
        StringBuilder escapedQuery = new StringBuilder();
        Matcher termMatcher = TERM_PATTERN.matcher(q);
        while (termMatcher.find()) {
            String field = termMatcher.group("field");
            String text = termMatcher.group("text");

            if (field == null) {
                escapedQuery.append(QueryParser.escape(text));
                escapedQuery.append(" ");
                continue;
            }
            escapedQuery.append(field);
            escapedQuery.append(QueryParser.escape(text));
            escapedQuery.append(" ");
        }
        return escapedQuery.toString().strip();
    }
}

class Pair<T, S, Q> {
    public final T first;
    public final S second;
    public final Q third;

    Pair(T first, S second, Q third) {
        this.first = first;
        this.second = second;
        this.third = third;
    }
}
