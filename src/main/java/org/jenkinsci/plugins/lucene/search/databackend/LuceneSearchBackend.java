package org.jenkinsci.plugins.lucene.search.databackend;

import com.google.common.collect.TreeMultimap;

import hudson.model.AbstractBuild;
import hudson.model.BallColor;
import hudson.model.Job;
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
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
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
import org.apache.lucene.util.Version;
import org.jenkinsci.plugins.lucene.search.Field;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchExtension;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static org.jenkinsci.plugins.lucene.search.Field.*;

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

    private static final Version LUCENE_VERSION = Version.LUCENE_4_9;
    private static final int MAX_HITS_PER_PAGE = 100;

    private final Directory index;
    private final Analyzer analyzer;
    private final IndexWriter dbWriter;
    private final File indexPath;
    private DirectoryReader reader;

    public LuceneSearchBackend(final File indexPath) throws IOException {
        super(SearchBackendEngine.LUCENE);
        this.indexPath = indexPath;
        analyzer = new StandardAnalyzer(LUCENE_VERSION, CharArraySet.EMPTY_SET);
        index = FSDirectory.open(indexPath);
        IndexWriterConfig config = new IndexWriterConfig(LUCENE_VERSION, analyzer);
        dbWriter = new IndexWriter(index, config);
        updateReader();
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
    public SearchBackend reconfigure(final Map<String, Object> newConfig) {
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

    private void updateReader() throws IOException {
        dbWriter.commit();
        reader = DirectoryReader.open(index);
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
            MultiFieldQueryParser queryParser = getQueryParser();
            Query q = queryParser.parse(query).rewrite(reader);

            IndexSearcher searcher = new IndexSearcher(reader);
            TopScoreDocCollector collector = TopScoreDocCollector.create(MAX_HITS_PER_PAGE, true);
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

                luceneSearchResultImpl.add(new FreeTextSearchItemImplementation(doc.get(PROJECT_NAME.fieldName), doc
                        .get(BUILD_NUMBER.fieldName), bestFragments, buildIcon.getImage()));
            }
        } catch (ParseException e) {
            // Do nothing
        } catch (IOException e) {
            // Do nothing
        }
        return luceneSearchResultImpl;
    }

    private MultiFieldQueryParser getQueryParser() {
        MultiFieldQueryParser queryParser = new MultiFieldQueryParser(LUCENE_VERSION, getAllDefaultSearchableFields(),
                analyzer) {
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
        queryParser.setLowercaseExpandedTerms(true);
        return queryParser;
    }

    @Override
    public void storeBuild(final AbstractBuild<?, ?> build, Document oldDoc) throws IOException {
        try {
            Document doc = new Document();
            for (Field field : Field.values()) {
                org.apache.lucene.document.Field.Store store = field.persist ? STORE : DONT_STORE;
                Object fieldValue = field.getValue(build);
                if (fieldValue == null && oldDoc != null) {
                    fieldValue = oldDoc.get(field.fieldName);
                }
                if (fieldValue != null) {
                    switch (FIELD_TYPE_MAP.get(field)) {
                    case LONG:
                        doc.add(new LongField(field.fieldName, fieldValue == null ? 0 : ((Number) fieldValue)
                                .longValue(), store));
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
            // TODO Add the following data
            // build.getCulprits()
            // EnvVars a = build.getEnvironment(listener);

            for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {
                Object fieldValue = extension.getTextResult(build);
                if (fieldValue == null && oldDoc != null) {
                    fieldValue = oldDoc.get(extension.getKeyword());
                }
                if (fieldValue != null) {
                    doc.add(new TextField(extension.getKeyword(), extension.getTextResult(build), (extension
                            .isPersist()) ? STORE : DONT_STORE));
                }
            }

            dbWriter.addDocument(doc);
        } finally {
            updateReader();
        }
    }

    @Override
    public Document removeBuild(final AbstractBuild<?, ?> build) {
        try {
            Term term = new Term(Field.ID.fieldName, build.getId());
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs search = searcher.search(new TermQuery(term), 1);
            Document doc = null;
            if (search.scoreDocs.length > 0) {
                doc = searcher.doc(search.scoreDocs[0].doc);
                dbWriter.deleteDocuments(term);
                updateReader();
            }
            return doc;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void cleanDeletedBuilds(Progress progress, Job job) {
        try {
            int firstBuildNumber = job.getFirstBuild().getNumber();
            IndexSearcher searcher = new IndexSearcher(reader);
            Term term = new Term(Field.PROJECT_NAME.fieldName, job.getName().toLowerCase(LOCALE));
            Query q = new TermQuery(term).rewrite(reader);
            TopDocs topDocs = searcher.search(q, 9999999);

            for (int i = 0; i < topDocs.scoreDocs.length; i++) {
                Document doc = searcher.doc(topDocs.scoreDocs[i].doc);
                progress.setMax(reader.maxDoc());
                progress.setCurrent(i);
                Integer buildNumber = Integer.valueOf(doc.get(BUILD_NUMBER.fieldName));
                if (firstBuildNumber > buildNumber) {
                    String id = doc.get(ID.fieldName);
                    dbWriter.deleteDocuments(new Term(ID.fieldName, id));
                }
            }
            progress.setSuccessfullyCompleted();
            updateReader();
        } catch (IOException e) {
            progress.completedWithErrors(e);
            LOGGER.error("Failed to delete cleaned builds", e);
        } finally {
            progress.setFinished();
        }
    }

    @Override
    public void deleteJob(String jobName) {
        try {
            Term term = new Term(PROJECT_NAME.fieldName, jobName.toLowerCase(LOCALE));
            dbWriter.deleteDocuments(term);
            updateReader();
        } catch (IOException e) {
            LOGGER.error("Could not delete job", e);
        }
    }

    @Override
    public List<SearchFieldDefinition> getAllFieldDefinitions() throws IOException {
        Map<String, Boolean> fieldNames = new LinkedHashMap<String, Boolean>();
        for (Field field : Field.values()) {
            fieldNames.put(field.fieldName, field.persist);
        }
        for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {
            fieldNames.put(extension.getKeyword(), extension.isPersist());
        }

        List<SearchFieldDefinition> definitions = new ArrayList<SearchFieldDefinition>();
        for (Map.Entry<String, Boolean> fieldEntry : fieldNames.entrySet()) {
            if (fieldEntry.getValue()) {
                // This is a persisted field (i.e. we can get values)
                IndexSearcher searcher = new IndexSearcher(reader);
                DistinctCollector collector = new LengthLimitedDistinctCollector(fieldEntry.getKey(), searcher, 30);
                searcher.search(new MatchAllDocsQuery(), collector);
                Set<String> distinctData = collector.getDistinctData();
                definitions.add(new SearchFieldDefinition(fieldEntry.getKey(), true, distinctData));
            } else {
                definitions.add(new SearchFieldDefinition(fieldEntry.getKey(), false, Collections.EMPTY_LIST));
            }
        }
        return definitions;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void cleanDeletedJobs(Progress progress) {
        try {
            Set<String> jobNames = new HashSet<String>();
            for (Job job : Jenkins.getInstance().getAllItems(Job.class)) {
                jobNames.add(job.getName());
            }
            progress.setMax(jobNames.size());
            IndexSearcher searcher = new IndexSearcher(reader);
            DistinctCollector distinctCollector = new DistinctCollector(PROJECT_NAME.fieldName, searcher);
            searcher.search(new MatchAllDocsQuery(), distinctCollector);
            int i = 0;
            for (String jobName : distinctCollector.getDistinctData()) {
                progress.setCurrent(i);
                if (!jobNames.contains(jobName)) {
                    deleteJob(jobName);
                }
                i++;
            }
            updateReader();
            progress.setSuccessfullyCompleted();
        } catch (IOException e) {
            progress.completedWithErrors(e);
            LOGGER.error("Failed to clean deleted jobs", e);
        } finally {
            progress.setFinished();
        }
    }

}