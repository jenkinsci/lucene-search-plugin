package org.jenkinsci.plugins.lucene.search.databackend;

import hudson.model.Run;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import org.apache.lucene.search.highlight.*;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.jenkinsci.plugins.lucene.search.Field;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchExtension;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;

import static org.jenkinsci.plugins.lucene.search.Field.*;

public class LuceneSearchBackend extends SearchBackend<Document> {
    private static final Logger LOGGER = Logger.getLogger(LuceneSearchBackend.class);

    private static final int MAX_NUM_FRAGMENTS = 5;
    private static final String[] EMPTY_ARRAY = new String[0];
    private static final Locale LOCALE = Locale.ENGLISH;
    private static final Pattern TERM_PATTERN = Pattern.compile("(?<field>\\S+:)?(?<text>[^\\\"]\\S*|\\\".+?\\\")\\s*");

    private static final org.apache.lucene.document.Field.Store DONT_STORE = org.apache.lucene.document.Field.Store.NO;
    private static final org.apache.lucene.document.Field.Store STORE = org.apache.lucene.document.Field.Store.YES;

    private enum LuceneFieldType {
        STRING, LONG, TEXT
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

    private static final Comparator<Integer> INT_COMPARATOR = new Comparator<Integer>() {
        @Override
        public int compare(Integer o1, Integer o2) {
            return o2.compareTo(o1);
        }
    };

    private static final int MAX_HITS_PER_PAGE = 100;

    private final Directory index;
    private final Analyzer analyzer;
    private final IndexWriter dbWriter;
    private volatile ScoreDoc lastDoc;

    public LuceneSearchBackend(final File indexPath) throws IOException {
        analyzer = new CaseSensitiveAnalyzer();
        index = FSDirectory.open(indexPath.toPath());
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        dbWriter = new IndexWriter(index, config);
        dbWriter.commit();
    }

    public static LuceneSearchBackend create(final Map<String, Object> config) {
        try {
            return new LuceneSearchBackend(getIndexPath(config));
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
        IOUtils.closeQuietly(dbWriter);
        IOUtils.closeQuietly(index);
    }

    private Long getWithDefault(String number, Long defaultNumber) {
        if (number != null) {
            Long l = Long.getLong(number);
            if (l != null) {
                return l;
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

    private Pair<Query, Query, Boolean> parseQuery(String q, IndexSearcher searcher) throws ParseException, IOException {

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
        return new Pair<>(query.rewrite(searcher.getIndexReader()),
                highlight.rewrite(searcher.getIndexReader()),
                fields.contains(CONSOLE.fieldName));
    }

    @Override
    public List<FreeTextSearchItemImplementation> getHits(String q, boolean searchNext) {
        List<FreeTextSearchItemImplementation> luceneSearchResultImpl = new ArrayList<>();
        try {
            IndexReader reader = DirectoryReader.open(index);
            IndexSearcher searcher = new IndexSearcher(reader);
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
            TreeMap<Integer, Document> docs = new TreeMap<>(INT_COMPARATOR);

            for (ScoreDoc hit : hits) {
                Document doc = searcher.doc(hit.doc);
                docs.put(Integer.parseInt(doc.get(BUILD_NUMBER.fieldName)), doc);
            }

            for (Document doc : docs.values()) {
                String[] bestFragments = EMPTY_ARRAY;
                try {
                    bestFragments = highlighter.getBestFragments(analyzer, CONSOLE.fieldName,
                            doc.get(CONSOLE.fieldName), MAX_NUM_FRAGMENTS);
                } catch (InvalidTokenOffsetsException e) {
                    LOGGER.debug("Failed to find bestFragments", e);
                }

                String projectName = doc.get(PROJECT_NAME.fieldName);
                String buildNumber = doc.get(BUILD_NUMBER.fieldName);
                String searchName = doc.get(BUILD_DISPLAY_NAME.fieldName);

                String url = "/job/" + projectName + "/" + buildNumber + "/";
                luceneSearchResultImpl.add(new FreeTextSearchItemImplementation(searchName,
                        projectName,
                        bestFragments,
                        url,
                        isShowConsole));
            }
            reader.close();
        } catch (ParseException e) {
//            LOGGER.warn("Search Parsing Error: ", e);
        } catch (IOException e) {
            LOGGER.warn("Search IO Error: ", e);
        } catch (AlreadyClosedException e) {
            LOGGER.warn("IndexReader is closed: ", e);
        }
        return luceneSearchResultImpl;
    }

    private MultiFieldQueryParser getQueryParser() {
        MultiFieldQueryParser queryParser = new MultiFieldQueryParser(getAllDefaultSearchableFields(), analyzer) {
            @Override
            protected Query getRangeQuery(String field, String part1, String part2, boolean startInclusive,
                                          boolean endInclusive) throws ParseException {
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
        queryParser.setMultiTermRewriteMethod(MultiTermQuery.SCORING_BOOLEAN_REWRITE);
        return queryParser;
    }

    @Override
    public void storeBuild(final Run<?, ?> run) throws IOException {
        try {
            Document doc = new Document();
            for (Field field : Field.values()) {
                org.apache.lucene.document.Field.Store store = field.persist ? STORE : DONT_STORE;
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
                            throw new IllegalArgumentException("Don't know how to handle " + FIELD_TYPE_MAP.get(field));
                    }
                }
            }

            for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {
                try {
                    Object fieldValue = extension.getTextResult(run);
                    if (fieldValue != null) {
                        doc.add(new TextField(extension.getKeyword(), extension.getTextResult(run), (extension
                                .isPersist()) ? STORE : DONT_STORE));
                    }
                } catch (Throwable t) {
                    //We don't want to crash the collection of log from other plugin extensions if we happen to add a plugin that crashes while collecting the logs.
                    LOGGER.warn("CRASH: " + extension.getClass().getName() + ", " + extension.getKeyword() + t);
                }
            }
            dbWriter.addDocument(doc);
        } finally {
            dbWriter.commit();
        }
    }

    public Query getRunQuery(Run<?, ?> run) throws ParseException {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(getQueryParser()
                .parse(PROJECT_NAME.fieldName + ":" + QueryParser.escape(run.getParent().getDisplayName())), BooleanClause.Occur.MUST)
                .add(getQueryParser()
                        .parse(BUILD_NUMBER.fieldName + ":" + run.getNumber()), BooleanClause.Occur.MUST);
        return builder.build();
    }

    @Override
    public boolean findRunIndex(Run<?, ?> run) {
        try {
            Query query = getRunQuery(run);
            IndexReader reader = DirectoryReader.open(index);
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs docs = searcher.search(query, 1);
            reader.close();
            return docs.scoreDocs.length > 0;
        } catch (ParseException e) {
            LOGGER.warn("findRunIndex: " + e);
        } catch (IOException e) {
            LOGGER.warn("findRunIndex: " + e);
        }
        return false;
    }

    @Override
    public void removeBuild(Run<?, ?> run) throws IOException {
        try {
            dbWriter.deleteDocuments(getRunQuery(run));
            dbWriter.commit();
        } catch (ParseException e) {
            LOGGER.warn("removeBuild: " + e);
        }
    }

    @Override
    public void deleteJob(String jobName) throws IOException {
        try {
            Query query = getQueryParser().parse(PROJECT_NAME.fieldName + ":" + QueryParser.escape(jobName));
            dbWriter.deleteDocuments(query);
            dbWriter.commit();
        } catch (IOException e) {
            LOGGER.error("Could not delete job", e);
        } catch (ParseException e) {
            //
        }
    }

    @Override
    public void cleanAllJob(ManagerProgress progress) {
        Progress currentProgress = progress.beginCleanJob();
        try {
            IndexReader reader = DirectoryReader.open(index);
            currentProgress.setCurrent(reader.numDocs());
            dbWriter.deleteAll();
            dbWriter.commit();
            reader.close();
            progress.setSuccessfullyCompleted();
        } catch (IOException e) {
            progress.completedWithErrors(e);
        } finally {
            currentProgress.setFinished();
            progress.jobComplete();
        }
    }

    static String escapeQuery(String q) {
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