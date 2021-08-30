package org.jenkinsci.plugins.lucene.search.databackend;

import com.google.common.io.Resources;
import hudson.search.Search;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendConfiguration;
import org.jenkinsci.plugins.lucene.search.management.LuceneManager;
import org.jvnet.hudson.test.JenkinsRule;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;

public class JenkinsSearchBackend {

    private final JenkinsRule rule;
    private final ExecutorService backgroundWorker;

    JenkinsSearchBackend(JenkinsRule rule, ExecutorService backgroundWorker) {
        this.rule = rule;
        this.backgroundWorker = backgroundWorker;
    }

    public void setLuceneBackend(boolean useSecurity) throws IOException, URISyntaxException, SAXException {
        SearchBackendConfiguration searchBackendConfiguration = GlobalConfiguration.all().get(
                SearchBackendConfiguration.class);
        searchBackendConfiguration.setUseSecurity(useSecurity);
        searchBackendConfiguration.reconfigure();
    }

    public Search.Result search(String query) throws IOException, SAXException {
        URL status = new URL(rule.getURL(), "search/suggest?query=" + query);
        String jsonString = Resources.toString(status, Charset.defaultCharset());
        Search.Result list = (Search.Result) JSONObject.fromObject(jsonString).toBean(Search.Result.class);
        return list;
    }

    public LuceneManager.JSReturnCollection getRebuildStatus(URL url) throws IOException {
        String jsonString = Resources.toString(url, Charset.defaultCharset());
        return (LuceneManager.JSReturnCollection) JSONObject.fromObject(jsonString).toBean(
                LuceneManager.JSReturnCollection.class);
    }

    public ExecutorService getBackgroundWorker() {
        return backgroundWorker;
    }
}
