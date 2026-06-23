package org.jenkinsci.plugins.lucene.search.databackend;

import com.google.common.io.Resources;
import hudson.search.Search;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendConfiguration;
import org.jenkinsci.plugins.lucene.search.management.LuceneManager;
import org.jvnet.hudson.test.JenkinsRule;

class JenkinsSearchBackend {

    private final JenkinsRule rule;
    private final ExecutorService backgroundWorker;

    JenkinsSearchBackend(JenkinsRule rule, ExecutorService backgroundWorker) {
        this.rule = rule;
        this.backgroundWorker = backgroundWorker;
    }

    void setLuceneBackend(boolean useSecurity) throws Exception {
        SearchBackendConfiguration searchBackendConfiguration =
                GlobalConfiguration.all().get(SearchBackendConfiguration.class);
        searchBackendConfiguration.setUseSecurity(useSecurity);
        searchBackendConfiguration.reconfigure();
    }

    Search.Result search(String query) throws Exception {
        URL status = new URL(rule.getURL(), "search/suggest?query=" + query);
        String jsonString = Resources.toString(status, Charset.defaultCharset());
        return (Search.Result) JSONObject.fromObject(jsonString).toBean(Search.Result.class);
    }

    LuceneManager.JSReturnCollection getRebuildStatus(URL url) throws Exception {
        JenkinsRule.WebClient wc = rule.createWebClient();
        String jsonString = wc.postJSON(url.toString(), new JSONObject()).getContentAsString();
        return (LuceneManager.JSReturnCollection)
                JSONObject.fromObject(jsonString).toBean(LuceneManager.JSReturnCollection.class);
    }

    public ExecutorService getBackgroundWorker() {
        return backgroundWorker;
    }
}
