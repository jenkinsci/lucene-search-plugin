package org.jenkinsci.plugins.lucene.search.databackend;

import com.google.common.io.Resources;
import hudson.model.FreeStyleProject;
import hudson.search.Search;
import hudson.tasks.Shell;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendConfiguration;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;
import org.jenkinsci.plugins.lucene.search.management.LuceneManager;
import org.jvnet.hudson.test.JenkinsRule;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JenkinsSearchBackend {

    private final JenkinsRule rule;
    private final ExecutorService backgroundWorker;

    JenkinsSearchBackend(JenkinsRule rule, ExecutorService backgroundWorker) {
        this.rule = rule;
        this.backgroundWorker = backgroundWorker;
    }

    public void setSolrBackend(boolean useSecurity, int port) throws IOException, URISyntaxException {
        SearchBackendConfiguration searchBackendConfiguration = GlobalConfiguration.all().get(
                SearchBackendConfiguration.class);
        searchBackendConfiguration.setUseSecurity(useSecurity);
        searchBackendConfiguration.setSearchBackend(SearchBackendEngine.SOLR);
        searchBackendConfiguration.setSolrCollection("collection1");
        searchBackendConfiguration.setSolrUrl(searchBackendConfiguration.makeSolrUrl("http://127.0.0.1:" + port));
        searchBackendConfiguration.reconfigure();
    }

    public void setLuceneBackend(boolean useSecurity) throws IOException, URISyntaxException, SAXException {
        SearchBackendConfiguration searchBackendConfiguration = GlobalConfiguration.all().get(
                SearchBackendConfiguration.class);
        searchBackendConfiguration.setUseSecurity(useSecurity);
        searchBackendConfiguration.setSearchBackend(SearchBackendEngine.LUCENE);
        searchBackendConfiguration.reconfigure();
    }

    public Search.Result search(String query) throws IOException, SAXException {
        URL status = new URL(rule.getURL(), "search/suggest?query=" + query);
        String jsonString = Resources.toString(status, Charset.defaultCharset());
        Search.Result list = (Search.Result) JSONObject.fromObject(jsonString).toBean(Search.Result.class);
        return list;
    }

    public void rebuildDatabase() throws IOException, SAXException, InterruptedException, ExecutionException {
        URL statusUrl = new URL(rule.getURL(), "lucenesearchmanager/status");
        final URL rebuildUrl = new URL(rule.getURL(), "lucenesearchmanager/rebuildDatabase");
        Future<Boolean> databaseRebuild = backgroundWorker.submit(new Callable<Boolean>() {

            @Override
            public Boolean call() throws Exception {
                LuceneManager.JSReturnCollection jsonObject = getRebuildStatus(rebuildUrl);
                assertEquals(0, jsonObject.code);
                return true;
            }
        });
        LuceneManager.JSReturnCollection jsonObject = getRebuildStatus(statusUrl);
        while (jsonObject.running || jsonObject.neverStarted) {
            Thread.sleep(1000);
            jsonObject = getRebuildStatus(statusUrl);
        }
        assertEquals(0, jsonObject.code);
        assertTrue("Something went wrong with rebuilding database", databaseRebuild.get());
    }

    public LuceneManager.JSReturnCollection getRebuildStatus(URL url) throws IOException {
        String jsonString = Resources.toString(url, Charset.defaultCharset());
        return (LuceneManager.JSReturnCollection) JSONObject.fromObject(jsonString).toBean(
                LuceneManager.JSReturnCollection.class);
    }
}
