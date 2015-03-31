package org.jenkinsci.plugins.lucene.search.databackend;

import jenkins.model.GlobalConfiguration;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.fs.FileUtil;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.core.ConfigSolr;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrResourceLoader;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;

public class SolrSearchBackendTest {

    @Rule
    public JenkinsRule rule = new JenkinsRule();

    int solrPort;
    private ExecutorService backgroundWorker;
    private JenkinsSearchBackend jenkinsSearchBackend;
    private EmbeddedSolrServer solrServer;

    @Before
    public void setup() throws Exception {
        solrPort = findFreePort();

        FileUtils.deleteQuietly(new File("target/solr/"));
        FileUtils.copyDirectory(new File("src/main/resources/solr/"), new File("target/solr/"));

        SolrResourceLoader solrResourceLoader = new SolrResourceLoader(new File("target/solr").getCanonicalPath());
        String configSolrXml = "<solr>"
                + "  <solrcloud>"
                + "    <str name=\"host\">${host:}</str>"
                + "    <int name=\"hostPort\">" + solrPort + "</int>"
                + "    <str name=\"hostContext\">${hostContext:solr}</str>"
                + "    <int name=\"zkClientTimeout\">${zkClientTimeout:30000}</int>"
                + "    <bool name=\"genericCoreNodeNames\">${genericCoreNodeNames:true}</bool>"
                + "  </solrcloud>"
                + "  <shardHandlerFactory name=\"shardHandlerFactory\""
                + "    class=\"HttpShardHandlerFactory\">"
                + "    <int name=\"socketTimeout\">${socketTimeout:0}</int>"
                + "    <int name=\"connTimeout\">${connTimeout:0}</int>"
                + "  </shardHandlerFactory>"
                + "</solr>";
        ConfigSolr config = ConfigSolr.fromString(solrResourceLoader, configSolrXml);
        CoreContainer container = new CoreContainer(solrResourceLoader, config);
//        CoreContainer container = new CoreContainer("testdata/solr");
        container.load();
        solrServer = new EmbeddedSolrServer(container, "collection1");
        assertEquals(200, solrServer.ping().getStatus());

        backgroundWorker = Executors.newFixedThreadPool(1);
        jenkinsSearchBackend = new JenkinsSearchBackend(rule, backgroundWorker);
    }

    @After
    public void tearDown() {
        backgroundWorker.shutdownNow();
        solrServer.shutdown();
    }

    private int findFreePort() throws IOException {
        Random rnd = new Random();
        for (int i = 0; i < 100; i++) {
            int testPort = rnd.nextInt(64000) + 1024;
            try {
                ServerSocket serverSocket = new ServerSocket(testPort);
                serverSocket.close();
                return testPort;
            } catch (IOException ex) {
            }
        }
        throw new IOException("Could not find available port");
    }

    @Test(timeout = 10000)
    public void givenSolrWhenJobsWithBuildsAreExecutedThenTheyShouldBeSearchable()
            throws IOException, ExecutionException, InterruptedException, SAXException, URISyntaxException {
        jenkinsSearchBackend.setSolrBackend(false, solrPort);
        jenkinsSearchBackend.testBuildAndRebuild();
    }
}
