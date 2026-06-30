package org.jenkinsci.plugins.lucene.search.databackend;

import static org.jenkinsci.plugins.lucene.search.Field.*;

import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
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
    private static final Logger LOGGER = Logger.getLogger(LuceneSearchBackend.class);

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
            // A stale write.lock from a previous plugin load or unclean shutdown
            // (e.g. Jenkins restart where instance was transient) can block
            // creation. Force-unlock and retry.
            LOGGER.warn("Stale lock file on Lucene index at " + indexPath
                    + ", force unlocking: " + e.getMessage());
            IOUtils.closeQuietly(index);
            try {
                java.nio.file.Files.deleteIfExists(indexPath.toPath().resolve("write.lock"));
            } catch (IOException ignored) {
                // best-effort
            }
            index = FSDirectory.open(indexPath.toPath());
            // IndexWriterConfig is consumed on first use — must create a fresh one.
            config = new IndexWriterConfig(analyzer);
            dbWriter = new IndexWriter(index, config);
        } catch (IllegalArgumentException e) {
            // The existing index may use an incompatible codec (e.g., Lucene87 after
            // upgrading from 8.x) or may be corrupt. Delete it and start fresh.
            LOGGER.warn("Failed to open existing Lucene index at "
                    + indexPath
                    + ", deleting and recreating: "
                    + e.getMessage());
            IOUtils.closeQuietly(index);
            deleteDirectory(indexPath);
            index = FSDirectory.open(indexPath.toPath());
            dbWriter = new IndexWriter(index, config);
        }
        dbWriter.commit();
        searcherManager = new SearcherManager(dbWriter, null);
        jenkins = Jenkins.get();
    }

    private static void deleteDirectory(File directory) throws IOException {
        if (directory.exists()) {
            org.apache.commons.io.FileUtils.deleteDirectory(directory);
        }
    }

    public static LuceneSearchBackend create(final Map<String, Object> config) {
        try {
            boolean shouldCollect = false;
            if (config.containsKey("collectBuildLogs")) {
                shouldCollect = (boolean) config.get("collectBuildLogs");
            }
            return new LuceneSearchBackend(getIndexPath(config), shouldCollect);
        } catch (IOException e) {
            LOGGER.error("create lucene search backend failed: " + e);
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
                    LOGGER.debug("Failed to find bestFragments", e);
                }

                String projectName = doc.get(PROJECT_NAME.fieldName);
                String buildNumber = doc.get(BUILD_NUMBER.fieldName);
                String searchName = doc.get(PROJECT_NAME.fieldName) + doc.get(BUILD_DISPLAY_NAME.fieldName);

                Item jobItem = jenkins.getItemByFullName(projectName);
                if (jobItem == null) {
                    LOGGER.debug("Project not found (removed or renamed): " + projectName);
                    continue;
                }
                if (!(jobItem instanceof Job)) {
                    LOGGER.debug("Unknown project type for project name: " + projectName);
                    continue;
                }
                Job job = (Job) jobItem;
                Run build = job.getBuildByNumber(Integer.parseInt(buildNumber));
                if (build == null) {
                    LOGGER.debug(
                            "Build #" + buildNumber + " not found for project " + projectName + " (possibly removed)");
                    continue;
                }
                FreeTextSearchItemImplementation itemImpl = new FreeTextSearchItemImplementation(
                        searchName, projectName, bestFragments, build.getUrl(), isShowConsole);
                luceneSearchResultImpl.add(itemImpl);
            }
        } catch (ParseException e) {
            //            LOGGER.warn("Search Parsing Error: ", e);
        } catch (IOException e) {
            LOGGER.warn("Search IO Error: ", e);
        } catch (AlreadyClosedException e) {
            LOGGER.warn("IndexReader is closed: ", e);
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException e) {
                    LOGGER.warn("Failed to release searcher: ", e);
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
        LOGGER.debug("LuceneBackend.storeBuild: build=" + run.getFullDisplayName()
                + " number=" + run.getNumber()
                + " project=" + run.getParent().getFullName());
        Document doc = new Document();
        // Unique key for atomic upsert — updateDocument deletes any
        // existing doc with the same term before adding this one.
        doc.add(new StringField(UNIQUE_KEY_FIELD, uniqueKeyFor(run), STORE));
        for (Field field : Field.values()) {
            org.apache.lucene.document.Field.Store store = field.persist ? STORE : DONT_STORE;
            if (isConsoleField(field) && !collectBuildLogs) {
                LOGGER.debug("Skipping console log indexing for field: " + field.fieldName);
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
                LOGGER.warn("CRASH: " + extension.getClass().getName() + ", " + extension.getKeyword() + t);
            }
        }
        // Atomic upsert: delete any existing doc for this build, then add.
        // No gap between remove and add — the build never disappears from searches.
        dbWriter.updateDocument(uniqueTermFor(run), doc);
        LOGGER.debug("LuceneBackend.storeBuild: document upserted (updateDocument) for build="
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
            LOGGER.warn("findRunIndex: " + e);
        } finally {
            if (searcher != null) {
                try {
                    searcherManager.release(searcher);
                } catch (IOException e) {
                    LOGGER.warn("Failed to release searcher in findRunIndex: ", e);
                }
            }
        }
        return false;
    }

    @Override
    public void removeBuild(Run<?, ?> run) throws IOException {
        LOGGER.debug("LuceneBackend.removeBuild: build=" + run.getFullDisplayName()
                + " number=" + run.getNumber()
                + " project=" + run.getParent().getFullName());
        Term term = uniqueTermFor(run);
        LOGGER.debug("LuceneBackend.removeBuild: term=" + term);
        dbWriter.deleteDocuments(term);
    }

    @Override
    public void deleteJob(String jobName) throws IOException {
        LOGGER.debug("LuceneBackend.deleteJob: job=" + jobName);
        try {
            String[] parts = jobName.split("/");
            PhraseQuery.Builder phraseBuilder = new PhraseQuery.Builder();
            for (int i = 0; i < parts.length; i++) {
                phraseBuilder.add(new Term(PROJECT_NAME.fieldName, parts[i]), i);
            }
            dbWriter.deleteDocuments(phraseBuilder.build());
        } catch (IOException e) {
            LOGGER.error("Could not delete job", e);
        }
    }

    @Override
    public void commitWrites() throws IOException {
        LOGGER.debug("LuceneBackend.commitWrites: committing and refreshing searcher");
        dbWriter.commit();
        searcherManager.maybeRefresh();
        LOGGER.debug("LuceneBackend.commitWrites: done");
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
                    LOGGER.warn("Failed to release searcher in cleanAllJob: ", e);
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
