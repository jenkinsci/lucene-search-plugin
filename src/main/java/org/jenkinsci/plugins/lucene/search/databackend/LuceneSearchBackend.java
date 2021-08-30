package org.jenkinsci.plugins.lucene.search.databackend;

import static org.jenkinsci.plugins.lucene.search.Field.ARTIFACTS;
import static org.jenkinsci.plugins.lucene.search.Field.BALL_COLOR;
import static org.jenkinsci.plugins.lucene.search.Field.BUILD_NUMBER;
import static org.jenkinsci.plugins.lucene.search.Field.BUILT_ON;
import static org.jenkinsci.plugins.lucene.search.Field.CHANGE_LOG;
import static org.jenkinsci.plugins.lucene.search.Field.CONSOLE;
import static org.jenkinsci.plugins.lucene.search.Field.DURATION;
import static org.jenkinsci.plugins.lucene.search.Field.ID;
import static org.jenkinsci.plugins.lucene.search.Field.PROJECT_DISPLAY_NAME;
import static org.jenkinsci.plugins.lucene.search.Field.PROJECT_NAME;
import static org.jenkinsci.plugins.lucene.search.Field.RESULT;
import static org.jenkinsci.plugins.lucene.search.Field.START_CAUSE;
import static org.jenkinsci.plugins.lucene.search.Field.START_TIME;
import static org.jenkinsci.plugins.lucene.search.Field.URL;
import static org.jenkinsci.plugins.lucene.search.Field.getIndex;
import hudson.model.BallColor;
import hudson.model.Job;
import hudson.model.Run;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import jenkins.model.Jenkins;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.LongField;
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
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.jenkinsci.plugins.lucene.search.Field;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchExtension;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;

import com.google.common.collect.TreeMultimap;

public class LuceneSearchBackend extends SearchBackend<Document> {
    private static final Logger LOGGER = Logger.getLogger(LuceneSearchBackend.class);

    private static final int MAX_NUM_FRAGMENTS = 5;
    private static final String[] EMPTY_ARRAY = new String[0];
    private static final Locale LOCALE = Locale.ENGLISH;

    private static final org.apache.lucene.document.Field.Store DONT_STORE = org.apache.lucene.document.Field.Store.NO;
    private static final org.apache.lucene.document.Field.Store STORE = org.apache.lucene.document.Field.Store.YES;

    private enum LuceneFieldType {
        STRING, LONG, TEXT
    }

    static final Map<Field, LuceneFieldType> FIELD_TYPE_MAP;
    static {
        Map<Field, LuceneFieldType> types = new HashMap<Field, LuceneFieldType>();
        types.put(ID, LuceneFieldType.STRING);
        types.put(PROJECT_NAME, LuceneFieldType.TEXT);
        types.put(PROJECT_DISPLAY_NAME, LuceneFieldType.TEXT);
        types.put(BUILD_NUMBER, LuceneFieldType.LONG);
        types.put(RESULT, LuceneFieldType.TEXT);
        types.put(DURATION, LuceneFieldType.LONG);
        types.put(START_TIME, LuceneFieldType.LONG);
        types.put(BUILT_ON, LuceneFieldType.TEXT);
        types.put(START_CAUSE, LuceneFieldType.TEXT);
        types.put(BALL_COLOR, LuceneFieldType.STRING);
        types.put(CONSOLE, LuceneFieldType.TEXT);
        types.put(CHANGE_LOG, LuceneFieldType.TEXT);
        types.put(ARTIFACTS, LuceneFieldType.TEXT);
        types.put(URL, LuceneFieldType.TEXT);
        FIELD_TYPE_MAP = Collections.unmodifiableMap(types);
    }

    private static final Comparator<Float> FLOAT_COMPARATOR = new Comparator<Float>() {
        @Override
        public int compare(Float o1, Float o2) {
            return o2.compareTo(o1);
        }
    };

    private static final Comparator<Document> START_TIME_COMPARATOR = new Comparator<Document>() {
        private Long getStartTime(Document o) {
            IndexableField field = o.getField(START_TIME.fieldName);
            if (field != null) {
                return field.numericValue().longValue();
            }
            return 0l;
        }

        @Override
        public int compare(Document o1, Document o2) {
            return getStartTime(o2).compareTo(getStartTime(o1));
        }
    };

    private static final int MAX_HITS_PER_PAGE = 100;

    private final Directory index;
    private final Analyzer analyzer;
    private final IndexWriter dbWriter;
    private final File indexPath;

    public LuceneSearchBackend(final File indexPath) throws IOException {
        this.indexPath = indexPath;
        analyzer = new StandardAnalyzer(CharArraySet.EMPTY_SET);
        index = FSDirectory.open(indexPath.toPath());
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        dbWriter = new IndexWriter(index, config);
        dbWriter.commit();
    }

    public static LuceneSearchBackend create(final Map<String, Object> config) {
        try {
            return new LuceneSearchBackend(getIndexPath(config));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static File getIndexPath(final Map<String, Object> config) {
        return (File) config.get("lucenePath");
    }

    @Override
    public SearchBackend<Document> reconfigure(final Map<String, Object> newConfig) {
        if (getIndexPath(newConfig).equals(indexPath)) {
            return this;
        } else {
            close();
            return create(newConfig);
        }
    }

    public synchronized void close() {
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

    @Override
    public List<FreeTextSearchItemImplementation> getHits(String query, boolean includeHighlights) {
        List<FreeTextSearchItemImplementation> luceneSearchResultImpl = new ArrayList<FreeTextSearchItemImplementation>();
        try {
            IndexReader reader = DirectoryReader.open(index);
            MultiFieldQueryParser queryParser = getQueryParser();
            Query q = queryParser.parse(query).rewrite(reader);

            IndexSearcher searcher = new IndexSearcher(reader);
            TopScoreDocCollector collector = TopScoreDocCollector.create(MAX_HITS_PER_PAGE);
            QueryTermScorer scorer = new QueryTermScorer(q);
            Highlighter highlighter = new Highlighter(new SimpleHTMLFormatter(), scorer);
            searcher.search(q, collector);
            ScoreDoc[] hits = collector.topDocs().scoreDocs;
            TreeMultimap<Float, Document> docs = TreeMultimap.create(FLOAT_COMPARATOR, START_TIME_COMPARATOR);

            for (ScoreDoc hit : hits) {
                Document doc = searcher.doc(hit.doc);
                docs.put(hit.score, doc);
            }
            for (Document doc : docs.values()) {
                String[] bestFragments = EMPTY_ARRAY;
                if (includeHighlights) {
                    try {
                        bestFragments = highlighter.getBestFragments(analyzer, CONSOLE.fieldName,
                                doc.get(CONSOLE.fieldName), MAX_NUM_FRAGMENTS);
                    } catch (InvalidTokenOffsetsException e) {
                        LOGGER.warn("Failed to find bestFragments", e);
                    }
                }
                BallColor buildIcon = BallColor.GREY;
                String colorName = doc.get(BALL_COLOR.fieldName);
                if (colorName != null) {
                    buildIcon = BallColor.valueOf(colorName);
                }

                String projectName = doc.get(PROJECT_NAME.fieldName);
                String buildNumber = doc.get(BUILD_NUMBER.fieldName);

                String url;
                if (doc.get(URL.fieldName) != null) {
                    url = doc.get(URL.fieldName);
                } else {
                    url = "/job/" + projectName + "/" + buildNumber + "/";
                }

                luceneSearchResultImpl.add(new FreeTextSearchItemImplementation(projectName, buildNumber, bestFragments, buildIcon.getImage(), url));
            }
            reader.close();
        } catch (ParseException e) {
            // Do nothing
        } catch (IOException e) {
            // Do nothing
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
                    return NumericRangeQuery.newLongRange(field, min, max, true, true);
                } else if (field != null) {
                    return new TermQuery(new Term(field));
                }
                return super.getRangeQuery(null, part1, part2, startInclusive, endInclusive);
            }
        };
        queryParser.setDefaultOperator(QueryParser.Operator.AND);
        queryParser.setLocale(LOCALE);
        queryParser.setAnalyzeRangeTerms(true);
        queryParser.setAllowLeadingWildcard(true);
        queryParser.setLowercaseExpandedTerms(false);
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
                            doc.add(new LongField(field.fieldName, ((Number) fieldValue).longValue(), store));
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
                .parse(PROJECT_NAME.fieldName + ":" + run.getParent().getDisplayName()), BooleanClause.Occur.MUST)
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
            Query query = getQueryParser().parse(PROJECT_NAME.fieldName + ":" + jobName);
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