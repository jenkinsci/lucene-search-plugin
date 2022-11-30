package org.jenkinsci.plugins.lucene.search.config;

import com.google.common.annotations.VisibleForTesting;
import hudson.Extension;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

@Extension
public class SearchBackendConfiguration extends GlobalConfiguration {

    private static final String LUCENE_PATH = "lucenePath";
    private static final String USE_SECURITY = "useSecurity";

    @Inject
    private transient SearchBackendManager backendManager;

    private File lucenePath = new File(Jenkins.getInstance().getRootDir(), "luceneIndex");
    private boolean useSecurity = true;

    @DataBoundConstructor
    public SearchBackendConfiguration(final String lucenePath,
           boolean useSecurity) {
        this(new File(lucenePath), useSecurity);
    }

    public SearchBackendConfiguration(final File lucenePath, boolean useSecurity) {
        load();
        this.lucenePath = lucenePath;
        this.useSecurity = useSecurity;
    }

    public SearchBackendConfiguration() {
        load();
    }

    public String getLucenePath() {
        return lucenePath.toString();
    }

    public void setLucenePath(final File lucenePath) {
        this.lucenePath = lucenePath;
    }

    public FormValidation doCheckLucenePath(@QueryParameter final String lucenePath) {
        try {
            new File(lucenePath);
            return FormValidation.ok();
        } catch (RuntimeException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private List<String> getCollections(String baseUrl) throws IOException {
        HttpClient httpClient = HttpClientBuilder.create().build();
        String url = baseUrl + "/admin/cores?wt=json";
        HttpGet get = new HttpGet(url);
        HttpResponse response = httpClient.execute(get);
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new IOException("Failed to GET " + url);
        }
        InputStream content = response.getEntity().getContent();
        try {
            String jsonString = IOUtils.toString(content, "UTF-8");
            JSONObject json = JSONObject.fromObject(jsonString);
            return new ArrayList(json.getJSONObject("status").keySet());
        } finally {
            IOUtils.closeQuietly(content);
        }
    }

    @Override
    public boolean configure(final StaplerRequest req, final JSONObject json) throws FormException {
        JSONObject selectedJson = json.getJSONObject("searchBackend");
        if (selectedJson.containsKey(LUCENE_PATH)) {
            String lucenePath = selectedJson.getString(LUCENE_PATH);
            ensureNotError(doCheckLucenePath(lucenePath), LUCENE_PATH);
            setLucenePath(new File(lucenePath));
        }
        if (json.containsKey(USE_SECURITY)) {
            setUseSecurity(json.getBoolean(USE_SECURITY));
        }
        try {
            reconfigure();
        } catch (IOException e) {
            //
        }
        return super.configure(req, json);
    }

    @VisibleForTesting
    public void reconfigure() throws IOException {
        backendManager.reconfigure(getConfig());
        save();
    }

    private void ensureNotError(FormValidation formValidation, String field) throws FormException {
        if (formValidation.kind == FormValidation.Kind.ERROR) {
            throw new FormException("Incorrect search config field: " + field, field);
        }
    }

    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("lucenePath", lucenePath);
        return config;
    }

    public boolean isUseSecurity() {
        return useSecurity;
    }

    public void setUseSecurity(boolean useSecurity) {
        this.useSecurity = useSecurity;
    }
}
