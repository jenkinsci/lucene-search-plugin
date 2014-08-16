package org.jenkinsci.plugins.lucene.search;

import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.search.SearchResult;
import hudson.search.SuggestedItem;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import jenkins.model.Jenkins;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopScoreDocCollector;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

public class LuceneManager {

    private static final String IDX_CONSOLE = "console";
    private static final String IDX_PROJECTNAME = "projectName";
    private static final String IDX_BUILDNUMBER = "buildNumber";

    private static final Version LUCENE_VERSION = Version.LUCENE_4_9;
    private static final int MAXHITPERPAGE = 10;

    public static LuceneManager instance;

    private final Directory index;
    private final StandardAnalyzer analyzer;
    private final IndexWriter dbWriter;

    private DirectoryReader reader;

    public LuceneManager() throws IOException {
        analyzer = new StandardAnalyzer(LUCENE_VERSION);
        index = FSDirectory.open(new File(Jenkins.getInstance().getRootDir(), "luceneIndex"));
        IndexWriterConfig config = new IndexWriterConfig(LUCENE_VERSION, analyzer);
        dbWriter = new IndexWriter(index, config);
        updateReader();
    }

    private void updateReader() throws IOException {
        dbWriter.commit();
        reader = DirectoryReader.open(index);
    }

    public LuceneSearchResultImpl getHits(final String query) {
        LuceneSearchResultImpl luceneSearchResultImpl = new LuceneSearchResultImpl();
        try {
            Query q = new QueryParser(LUCENE_VERSION, IDX_CONSOLE,, analyzer).parse(query);

            IndexSearcher searcher = new IndexSearcher(reader);
            TopScoreDocCollector collector = TopScoreDocCollector.create(MAXHITPERPAGE, true);
            searcher.search(q, collector);
            ScoreDoc[] hits = collector.topDocs().scoreDocs;
            for (ScoreDoc hit : hits) {
                Document doc = searcher.doc(hit.doc);
                luceneSearchResultImpl.add(new SuggestedItem(new LuceneSearchItemImplementation(doc.get(IDX_PROJECTNAME), doc
                        .get(IDX_BUILDNUMBER))));
            }

        } catch (ParseException e) {
            // Do nothing
        } catch (IOException e) {
            // Do nothing
        }

        return luceneSearchResultImpl;

    }

    public void storeBuild(final AbstractBuild<?, ?> build, final BuildListener listener) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        build.getLogText().writeLogTo(0, byteArrayOutputStream);
        String consoleOutput = byteArrayOutputStream.toString();

        try {
            Document doc = new Document();

            doc.add(new StringField(IDX_PROJECTNAME, build.getProject().getName(), Field.Store.YES));
            doc.add(new StringField("projectDisplayName", build.getProject().getDisplayName(), Field.Store.YES));
            doc.add(new IntField(IDX_BUILDNUMBER, build.getNumber(), Field.Store.YES));
            //doc.add(new StringField("result", build.getResult().toString(), Field.Store.YES));

            // build.getChangeSet()
            // build.getBuiltOnStr()
            // build.getArtifacts()
            // build.getCulprits()
            // build.getDuration() // build.getTimeInMillis()
            // build.getStartTimeInMillis()
            // EnvVars a = build.getEnvironment(listener);
            // build.get

            doc.add(new TextField(IDX_CONSOLE, consoleOutput, Field.Store.YES));

            dbWriter.addDocument(doc);
        } finally {
            updateReader();
        }
    }

    public synchronized static LuceneManager getInstance() throws IOException {
        if (instance == null) {
            instance = new LuceneManager();
        }
        return instance;
    }

    private static class LuceneSearchResultImpl extends ArrayList<SuggestedItem> implements SearchResult {

        private static final long serialVersionUID = 1L;

        private final boolean hasMoreResults = false;

        public boolean hasMoreResults() {
            return hasMoreResults;
        }
    }

}