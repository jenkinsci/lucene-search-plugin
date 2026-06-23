package org.jenkinsci.plugins.lucene.search.databackend;

import com.google.common.io.Resources;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import hudson.search.Search;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendConfiguration;
import org.jenkinsci.plugins.lucene.search.management.LuceneManager;
import org.jvnet.hudson.test.JenkinsRule;

class JenkinsSearchBackend {

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(AtomicInteger.class, (JsonSerializer<AtomicInteger>)
                    (src, type, context) -> new JsonPrimitive(src.get()))
            .create();

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
        return GSON.fromJson(jsonString, Search.Result.class);
    }

    LuceneManager.JSReturnCollection getRebuildStatus(URL url) throws Exception {
        JenkinsRule.WebClient wc = rule.createWebClient();
        String jsonString = wc.postJSON(url.toString(), new JSONObject()).getContentAsString();
        return GSON.fromJson(jsonString, LuceneManager.JSReturnCollection.class);
    }

    public ExecutorService getBackgroundWorker() {
        return backgroundWorker;
    }
}
