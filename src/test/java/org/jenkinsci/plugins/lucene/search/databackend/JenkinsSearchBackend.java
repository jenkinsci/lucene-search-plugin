package org.jenkinsci.plugins.lucene.search.databackend;

import com.google.common.io.Resources;
import hudson.model.FreeStyleProject;
import hudson.search.Search;
import hudson.tasks.Shell;
import net.sf.json.JSONObject;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;
import org.jenkinsci.plugins.lucene.search.management.LuceneManager;
import org.junit.After;
import org.jvnet.hudson.test.JenkinsRule;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JenkinsSearchBackend {

    private final JenkinsRule rule;
    private final ExecutorService backgroundWorker;
    private final HttpClient httpClient = HttpClients.createDefault();

    JenkinsSearchBackend(JenkinsRule rule, ExecutorService backgroundWorker) {
        this.rule = rule;
        this.backgroundWorker = backgroundWorker;
    }

    public void setSolrBackend(boolean useSecurity, int port) throws IOException, URISyntaxException {
        JSONObject searchBackend = new JSONObject();
        searchBackend.put("solrUrl", "http://127.0.0.1:"+port+"/solr");
        searchBackend.put("solrCollection", "collection1");

        JSONObject configNode = new JSONObject();
        configNode.put("searchBackend", searchBackend);
        configNode.put("useSecurity", useSecurity);
        configNode.put("", SearchBackendEngine.SOLR.name());

        JSONObject result = new JSONObject();
        result.put("org-jenkinsci-plugins-lucene-search-config-SearchBackendConfiguration", configNode);

        URL statusUrl = new URL(rule.getURL(), "configSubmit");
        HttpPost post = new HttpPost(statusUrl.toURI());
        List<NameValuePair> params = new ArrayList<NameValuePair>(2);
        params.add(new BasicNameValuePair("json", result.toString()));
        //params.add(new BasicNameValuePair("configSubmit", "save"));
        post.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));
        //configure Jenkins to use Solr here

    }

    public void setLuceneBackend(boolean useSecurity) throws IOException, URISyntaxException {
        JSONObject configNode = new JSONObject();
        configNode.put("useSecurity", useSecurity);
        configNode.put("", SearchBackendEngine.LUCENE.name());

        JSONObject result = new JSONObject();
        result.put("org-jenkinsci-plugins-lucene-search-config-SearchBackendConfiguration", configNode);

        URL statusUrl = new URL(rule.getURL(), "configSubmit");
        HttpPost post = new HttpPost(statusUrl.toURI());
        List<NameValuePair> params = new ArrayList<NameValuePair>(2);
        params.add(new BasicNameValuePair("json", result.toString()));
        //params.add(new BasicNameValuePair("configSubmit", "save"));
        post.setEntity(new UrlEncodedFormEntity(params, "UTF-8"));
        //configure Jenkins to use Solr here
        HttpResponse execute = httpClient.execute(post);

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
        return (LuceneManager.JSReturnCollection) JSONObject.fromObject(jsonString).toBean(LuceneManager.JSReturnCollection.class);
    }

    public void testBuildAndRebuild()
            throws IOException, ExecutionException, InterruptedException, SAXException {
        assertEquals(0, search("echo").suggestions.size());
        FreeStyleProject project1 = rule.createFreeStyleProject("project1");
        project1.getBuildersList().add(new Shell("echo $BUILD_TAG\n"));
        // Building
        project1.scheduleBuild2(0).get();
        project1.scheduleBuild2(0).get();
        project1.scheduleBuild2(0).get();

        rule.createFreeStyleProject("project2");

        FreeStyleProject project3 = rule.createFreeStyleProject("project3");
        project3.getBuildersList().add(new Shell("cat $BUILD_TAG\n"));
        assertEquals(3, search("echo").suggestions.size());
        rebuildDatabase();
        assertEquals(3, search("echo").suggestions.size());
    }
}
