package org.jenkinsci.plugins.lucene.search.databackend;

import org.apache.commons.io.FileUtils;
import org.apache.solr.client.solrj.embedded.EmbeddedSolrServer;
import org.apache.solr.core.CoreContainer;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Ignore
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
        backgroundWorker = Executors.newFixedThreadPool(1);
        jenkinsSearchBackend = new JenkinsSearchBackend(rule, backgroundWorker);

        File solrWorkDir = new File("target/solr/").getAbsoluteFile().getCanonicalFile();
        FileUtils.deleteQuietly(solrWorkDir);
        FileUtils.copyDirectory(new File("src/test/resources/solr/"), solrWorkDir);

//        SolrResourceLoader solrResourceLoader = new SolrResourceLoader(new File().getCanonicalPath());
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
        File solrConfig = new File(solrWorkDir, "solr.xml");
        OutputStream out = new FileOutputStream(solrConfig);
        out.write(configSolrXml.getBytes("UTF-8"));
        out.close();

        //        ConfigSolr config = ConfigSolr.fromString(solrResourceLoader, configSolrXml);
//        CoreContainer container = new CoreContainer(solrResourceLoader, config);
        CoreContainer container = new CoreContainer(solrWorkDir.getPath());
        container.load();
        solrServer = new EmbeddedSolrServer(container, "collection1");
        solrServer.commit();
        System.err.println(configSolrXml);
//        assertEquals(200, solrServer.ping().getStatus());

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

    @Test(timeout = 1000000)
    public void givenSolrWhenJobsWithBuildsAreExecutedThenTheyShouldBeSearchable()
            throws IOException, ExecutionException, InterruptedException, SAXException, URISyntaxException {
        jenkinsSearchBackend.setSolrBackend(false, solrPort);
        jenkinsSearchBackend.testBuildAndRebuild();
    }
}
