package org.jenkinsci.plugins.lucene.search;

import hudson.model.AbstractBuild;
import hudson.model.Cause;
import hudson.search.SuggestedItem;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.search.highlight.Highlighter;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.apache.lucene.search.highlight.QueryTermScorer;
import org.apache.lucene.search.highlight.SimpleHTMLFormatter;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class LuceneSearchBackend implements SearchBackend {
    private static final int MAX_NUM_FRAGMENTS = 5;
    private static final String[] EMPTY_ARRAY = new String[0];

    private enum Index {
        CONSOLE("console"), PROJECT_NAME("projectname"), BUILD_NUMBER("buildnumber"), ID("id", false), PROJECT_DISPLAY_NAME(
                "projectdisplayname"), RESULT("result"), DURATION("duration", false), START_TIME("starttime", false), BUILT_ON(
                "builton"), START_CAUSE("startcause");
        public final String fieldName;
        public final boolean defaultSearchable;

        private Index(String fieldName) {
            this(fieldName, true);
        }

        private Index(String fieldName, boolean defaultSearchable) {
            this.fieldName = fieldName;
            this.defaultSearchable = defaultSearchable;
        }
    }

    private static final Version LUCENE_VERSION = Version.LUCENE_4_9;
    private static final int MAX_HITS_PER_PAGE = 100;

    private final Directory index;
    private final StandardAnalyzer analyzer;
    private final IndexWriter dbWriter;

    private DirectoryReader reader;
    private final File indexPath;

    public LuceneSearchBackend(final File indexPath) throws IOException {
        this.indexPath = indexPath;
        analyzer = new StandardAnalyzer(LUCENE_VERSION);
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

    @Override
    public SearchResultImpl getHits(final String query, final boolean includeHighlights) {
        SearchResultImpl luceneSearchResultImpl = new SearchResultImpl();
        try {
            getAllFields();
            Query q = new MultiFieldQueryParser(LUCENE_VERSION, getAllFields(), analyzer).parse(query);
            //.rewrite(reader);

            IndexSearcher searcher = new IndexSearcher(reader);
            TopScoreDocCollector collector = TopScoreDocCollector.create(MAX_HITS_PER_PAGE, true);
            QueryTermScorer scorer = new QueryTermScorer(q);
            Highlighter highlighter = new Highlighter(new SimpleHTMLFormatter(), scorer);
            searcher.search(q, collector);
            ScoreDoc[] hits = collector.topDocs().scoreDocs;
            for (ScoreDoc hit : hits) {
                Document doc = searcher.doc(hit.doc);
                String[] bestFragments = EMPTY_ARRAY;
                if (includeHighlights) {
                    try {
                        bestFragments = highlighter.getBestFragments(analyzer, "contents",
                                doc.get(Index.CONSOLE.fieldName), MAX_NUM_FRAGMENTS);
                    } catch (InvalidTokenOffsetsException e) {
                        e.printStackTrace();
                    }
                }
                luceneSearchResultImpl.add(new SuggestedItem(new FreeTextSearchItemImplementation(doc
                        .get(Index.PROJECT_NAME.fieldName), doc.get(Index.BUILD_NUMBER.fieldName), bestFragments)));
            }

        } catch (ParseException e) {
            // Do nothing
        } catch (IOException e) {
            // Do nothing
        }
        return luceneSearchResultImpl;
    }

    private String[] getAllFields() {
        List<String> fieldNames = new LinkedList<String>();
        for (Index field : Index.values()) {
            if (field.defaultSearchable) {
                fieldNames.add(field.fieldName);
            }
        }
        for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {
            if (extension.isDefaultSearchable()) {
                fieldNames.add(extension.getKeyword());
            }
        }
        return fieldNames.toArray(EMPTY_ARRAY);
    }

    @Override
    public void storeBuild(final AbstractBuild<?, ?> build) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        build.getLogText().writeLogTo(0, byteArrayOutputStream);
        String consoleOutput = byteArrayOutputStream.toString();

        try {
            Document doc = new Document();
            doc.add(new StringField(Index.ID.fieldName, build.getId(), Field.Store.YES));
            doc.add(new TextField(Index.PROJECT_NAME.fieldName, build.getProject().getName(), Field.Store.YES));
            doc.add(new TextField(Index.PROJECT_DISPLAY_NAME.fieldName, build.getProject().getDisplayName(),
                    Field.Store.YES));
            doc.add(new IntField(Index.BUILD_NUMBER.fieldName, build.getNumber(), Field.Store.YES));
            doc.add(new TextField(Index.RESULT.fieldName, build.getResult().toString(), Field.Store.YES));
            doc.add(new LongField(Index.DURATION.fieldName, build.getDuration(), Field.Store.NO));
            doc.add(new LongField(Index.START_TIME.fieldName, build.getStartTimeInMillis(), Field.Store.NO));
            doc.add(new TextField(Index.BUILT_ON.fieldName, build.getBuiltOnStr(), Field.Store.NO));
            StringBuilder shortDescriptions = new StringBuilder();
            for (Cause cause : build.getCauses()) {
                shortDescriptions.append(" ").append(cause.getShortDescription());
            }
            doc.add(new TextField(Index.START_CAUSE.fieldName, shortDescriptions.toString(), Field.Store.NO));

            // build.getChangeSet()
            // build.getCulprits()
            // EnvVars a = build.getEnvironment(listener);
            // build.get
            // build.getArtifacts()

            doc.add(new TextField(Index.CONSOLE.fieldName, consoleOutput, Field.Store.YES));

            for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {
                doc.add(new TextField(extension.getKeyword(), extension.getTextResult(build),
                        (extension.persist()) ? Field.Store.YES : Field.Store.NO));
            }

            dbWriter.addDocument(doc);
        } finally {
            updateReader();
        }
    }

    @Override
    public SearchBackendEngine getEngine() {
        return SearchBackendEngine.LUCENE;
    }

    @Override
    public void removeBuild(final AbstractBuild<?, ?> build) {
        try {
            dbWriter.deleteDocuments(new Term(Index.ID.fieldName, build.getId()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
