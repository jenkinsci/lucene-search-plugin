package org.jenkinsci.plugins.lucene.search;

import hudson.EnvVars;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;

import java.io.IOException;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.Version;

import com.trilead.ssh2.util.IOUtils;

public class LuceneManager {

    private static final Version LUCENE49 = Version.LUCENE_4_9;

    public static LuceneManager instance = null;

    private IndexWriterConfig config;

    private Directory index;

    public LuceneManager() {
        StandardAnalyzer analyzer = new StandardAnalyzer(LUCENE49);
        index = new RAMDirectory();
        config = new IndexWriterConfig(LUCENE49, analyzer);
    }

    public void storeBuild(AbstractBuild<?, ?> build, BuildListener listener) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        build.getLogText().writeLogTo(0, byteArrayOutputStream);
        String consoleOutput = byteArrayOutputStream.toString();

        IndexWriter dbWriter = new IndexWriter(index, config);
        try {
            Document doc = new Document();

            doc.add(new StringField("projectName", build.getProject().getName(), Field.Store.YES));
            doc.add(new StringField("projectDisplayName", build.getProject().getDisplayName(), Field.Store.YES));
            doc.add(new IntField("buildNumber", build.getNumber(), Field.Store.YES));
            doc.add(new StringField("result", build.getResult().toString(), Field.Store.YES));

            // build.getChangeSet()
            // build.getBuiltOnStr()
            // build.getArtifacts()
            // build.getCulprits()
            // build.getDuration() // build.getTimeInMillis()
            // build.getStartTimeInMillis()
            // EnvVars a = build.getEnvironment(listener);
            // build.get

            doc.add(new TextField("console", consoleOutput, Field.Store.NO));

            dbWriter.addDocument(doc);
        } finally {
            IOUtils.closeQuietly(dbWriter);
        }
        System.err.println(consoleOutput);
    }

    public synchronized static LuceneManager getInstance() {
        if (instance == null) {
            instance = new LuceneManager();
        }
        return instance;
    }

}
