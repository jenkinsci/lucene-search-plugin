package org.jenkinsci.plugins.lucene.search.databackend;

import com.google.common.collect.TreeMultimap;
import hudson.model.AbstractBuild;
import hudson.model.BallColor;
import hudson.model.Cause;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
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
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopScoreDocCollector;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.jenkinsci.plugins.lucene.search.Field.BALL_COLOR;
import static org.jenkinsci.plugins.lucene.search.Field.BUILD_NUMBER;
import static org.jenkinsci.plugins.lucene.search.Field.CONSOLE;
import static org.jenkinsci.plugins.lucene.search.Field.PROJECT_NAME;
import static org.jenkinsci.plugins.lucene.search.Field.START_TIME;
import static org.jenkinsci.plugins.lucene.search.Field.getIndex;

public class LuceneSearchBackend extends SearchBackend {
    private static final int MAX_NUM_FRAGMENTS = 5;
    private static final String[] EMPTY_ARRAY = new String[0];

    private static final org.apache.lucene.document.Field.Store NO = org.apache.lucene.document.Field.Store.NO;
    private static final org.apache.lucene.document.Field.Store YES = org.apache.lucene.document.Field.Store.YES;

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

    private DirectoryReader reader;
    private final File indexPath;

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
            MultiFieldQueryParser queryParser = new MultiFieldQueryParser(LUCENE_VERSION, getAllDefaultSearchableFields(), analyzer) {
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
                    return super.getRangeQuery(field, part1, part2, startInclusive, endInclusive);
                }
            };
            queryParser.setDefaultOperator(QueryParser.Operator.AND);
            queryParser.setLocale(Locale.ENGLISH);
            queryParser.setAnalyzeRangeTerms(true);
            queryParser.setLowercaseExpandedTerms(true);
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
                System.err.println(hit.score + ", " + doc.get(BUILD_NUMBER.fieldName));
            }
            for (Document doc : docs.values()) {
                String[] bestFragments = EMPTY_ARRAY;
                if (includeHighlights) {
                    try {
                        bestFragments = highlighter.getBestFragments(analyzer, CONSOLE.fieldName,
                                doc.get(CONSOLE.fieldName), MAX_NUM_FRAGMENTS);
                    } catch (InvalidTokenOffsetsException e) {
                        e.printStackTrace();
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

    @Override
    public void storeBuild(final AbstractBuild<?, ?> build) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        build.getLogText().writeLogTo(0, byteArrayOutputStream);
        String consoleOutput = byteArrayOutputStream.toString();

        try {
            Document doc = new Document();
            doc.add(new StringField(Field.ID.fieldName, build.getId(), YES));
            doc.add(new TextField(Field.PROJECT_NAME.fieldName, build.getProject().getName(), YES));
            doc.add(new TextField(Field.PROJECT_DISPLAY_NAME.fieldName, build.getProject().getDisplayName(), YES));
            doc.add(new LongField(Field.BUILD_NUMBER.fieldName, build.getNumber(), YES));
            doc.add(new TextField(Field.RESULT.fieldName, build.getResult().toString(), YES));
            doc.add(new LongField(Field.DURATION.fieldName, build.getDuration(), NO));
            doc.add(new LongField(Field.START_TIME.fieldName, build.getStartTimeInMillis(), NO));
            doc.add(new TextField(Field.BUILT_ON.fieldName, build.getBuiltOnStr(), NO));
            StringBuilder shortDescriptions = new StringBuilder();
            for (Cause cause : build.getCauses()) {
                shortDescriptions.append(" ").append(cause.getShortDescription());
            }
            doc.add(new TextField(Field.START_CAUSE.fieldName, shortDescriptions.toString(), NO));
            doc.add(new StringField(Field.BALL_COLOR.fieldName, build.getIconColor().name(), YES));
            // TODO Add the following data
            // build.getChangeSet()
            // build.getCulprits()
            // EnvVars a = build.getEnvironment(listener);
            // build.get
            // build.getArtifacts()

            doc.add(new TextField(Field.CONSOLE.fieldName, consoleOutput, YES));

            for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {
                doc.add(new TextField(extension.getKeyword(), extension.getTextResult(build),
                        (extension.isPersist()) ? YES : NO));
            }

            dbWriter.addDocument(doc);
        } finally {
            updateReader();
        }
    }

    @Override
    public void removeBuild(final AbstractBuild<?, ?> build) {
        try {
            dbWriter.deleteDocuments(new Term(Field.ID.fieldName, build.getId()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}