package org.jenkinsci.plugins.lucene.search;

import hudson.model.AbstractBuild;
import hudson.model.Cause;
import hudson.search.SuggestedItem;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
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

public class LuceneSearchBackend implements SearchBackend {
    private static final int MAX_NUM_FRAGMENTS = 5;
    private static final String IDX_CONSOLE = "console";
    private static final String IDX_PROJECTNAME = "projectName";
    private static final String IDX_BUILDNUMBER = "buildNumber";
    private static final String IDX_ID = "ID";

    private static final Version LUCENE_VERSION = Version.LUCENE_4_9;
    private static final int MAXHITPERPAGE = 100;
    private static final String[] EMPTY_ARRAY = new String[0];

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
            Query q = new QueryParser(LUCENE_VERSION, IDX_CONSOLE, analyzer).parse(query).rewrite(reader);

            IndexSearcher searcher = new IndexSearcher(reader);
            TopScoreDocCollector collector = TopScoreDocCollector.create(MAXHITPERPAGE, true);
            QueryTermScorer scorer = new QueryTermScorer(q);
            Highlighter highlighter = new Highlighter(new SimpleHTMLFormatter(), scorer);
            searcher.search(q, collector);
            ScoreDoc[] hits = collector.topDocs().scoreDocs;
            for (ScoreDoc hit : hits) {
                Document doc = searcher.doc(hit.doc);
                String[] bestFragments = EMPTY_ARRAY;
                if (includeHighlights) {
                    try {
                        bestFragments = highlighter.getBestFragments(analyzer, "contents", doc.get(IDX_CONSOLE), MAX_NUM_FRAGMENTS);
                    } catch (InvalidTokenOffsetsException e) {
                        e.printStackTrace();
                    }
                }
                luceneSearchResultImpl.add(new SuggestedItem(new FreeTextSearchItemImplementation(doc.get(IDX_PROJECTNAME), doc
                        .get(IDX_BUILDNUMBER), bestFragments)));
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
            doc.add(new StringField(IDX_ID, build.getId(), Field.Store.YES));
            doc.add(new StringField(IDX_PROJECTNAME, build.getProject().getName(), Field.Store.YES));
            doc.add(new StringField("projectDisplayName", build.getProject().getDisplayName(), Field.Store.YES));
            doc.add(new IntField(IDX_BUILDNUMBER, build.getNumber(), Field.Store.YES));
            doc.add(new StringField("result", build.getResult().toString(), Field.Store.YES));
            doc.add(new LongField("duration", build.getDuration(), Field.Store.NO));
            doc.add(new LongField("startTime", build.getStartTimeInMillis(), Field.Store.NO));
            doc.add(new StringField("builtOn", build.getBuiltOnStr(), Field.Store.NO));
            StringBuilder shortDescriptions = new StringBuilder();
            for (Cause cause : build.getCauses()) {
                shortDescriptions.append(" ").append(cause.getShortDescription());
            }
            doc.add(new TextField("startCause", shortDescriptions.toString(), Field.Store.NO));

            // build.getChangeSet()
            // build.getCulprits()
            // EnvVars a = build.getEnvironment(listener);
            // build.get
            // build.getArtifacts()

            doc.add(new TextField(IDX_CONSOLE, consoleOutput, Field.Store.YES));

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
            dbWriter.deleteDocuments(new Term(IDX_ID, build.getId()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
