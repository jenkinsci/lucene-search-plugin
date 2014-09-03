package org.jenkinsci.plugins.lucene.search.config;

import hudson.Extension;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
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
import org.jenkinsci.plugins.lucene.search.SearchBackendManager;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

@Extension
public class SearchBackendConfiguration extends GlobalConfiguration {

    private static final String LUCENE_PATH = "lucenePath";
    private static final String SOLR_URL = "solrUrl";
    private static final String SOLR_COLLECTION = "solrCollection";

    @Inject
    private transient SearchBackendManager backendManager;

    private URI solrUrl = URI.create("http://127.0.0.1:8983/");
    private File lucenePath = new File(Jenkins.getInstance().getRootDir(), "luceneIndex");
    private String solrCollection = "collection1";
    private SearchBackendEngine searchBackend = SearchBackendEngine.LUCENE;

    @DataBoundConstructor
    public SearchBackendConfiguration(final String solrUrl, final String lucenePath, final String searchBackend,
            final String solrCollection) {
        this(URI.create(solrUrl), new File(lucenePath), SearchBackendEngine.valueOf(searchBackend), solrCollection);
    }

    public SearchBackendConfiguration(final URI solrUrl, final File lucenePath,
            final SearchBackendEngine searchBackend, final String solrCollection) {
        load();
        this.searchBackend = searchBackend;
        this.lucenePath = lucenePath;
        this.solrUrl = solrUrl;
        this.solrCollection = solrCollection;
    }

    public SearchBackendConfiguration() {
        load();
    }

    public String getSolrUrl() {
        return solrUrl.toString();
    }

    public void setSolrUrl(final URI solrUrl) {
        this.solrUrl = solrUrl;
    }

    public String getLucenePath() {
        return lucenePath.toString();
    }

    public void setLucenePath(final File lucenePath) {
        this.lucenePath = lucenePath;
    }

    public String getSearchBackend() {
        return searchBackend.toString();
    }

    public SearchBackendEngine getSearchBackendEngine() {
        return searchBackend;
    }

    public void setSearchBackend(final SearchBackendEngine searchBackend) {
        this.searchBackend = searchBackend;
    }

    public FormValidation doCheckLucenePath(@QueryParameter final String lucenePath) {
        try {
            new File(lucenePath);
            return FormValidation.ok();
        } catch (RuntimeException e) {
            return FormValidation.error(e.getMessage());
        }
    }

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

    private URI makeSolrUrl(final String solrUrlX) throws IOException {
        IOException e = null;
        String solrUrl = solrUrlX.replaceAll("/*$", "");
        for (String s : new String[] { solrUrl + "/solr", solrUrl }) {
            try {
                getCollections(s);
                return URI.create(s);
            } catch (IOException e2) {
                e = e2;
            }
        }
        throw e;
    }

    public FormValidation doCheckSolrUrl(@QueryParameter final String solrUrl) {
        try {
            URI uri = makeSolrUrl(solrUrl);
            if (solrUrl.equals(uri.toString())) {
                return FormValidation.ok();
            } else {
                return FormValidation.warning("Incomplete url, but solr was found at " + uri.toString());
            }
        } catch (IOException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    public FormValidation doCheckSolrCollection(@QueryParameter final String solrCollection) {
        try {
            List<String> collections = getCollections(solrUrl.toString());
            if (collections.contains(solrCollection)) {
                return FormValidation.ok();
            } else {
                return FormValidation.error("Collection not found among: " + collections);
            }
        } catch (IOException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    @Override
    public boolean configure(final StaplerRequest req, final JSONObject json)
            throws hudson.model.Descriptor.FormException {
        JSONObject selectedJson = json.getJSONObject("searchBackend");
        if (selectedJson.containsKey(SOLR_URL)) {
            String solrUrl = selectedJson.getString(SOLR_URL);
            ensureNotError(doCheckSolrUrl(solrUrl), SOLR_URL);
            try {
                setSolrUrl(makeSolrUrl(solrUrl));
            } catch (IOException e) {
                // Really shouldn't be possible, but this is the correct action, should it ever happen
                throw new FormException("Incorrect freetext config", SOLR_URL);
            }
        }
        if (selectedJson.containsKey(SOLR_COLLECTION)) {
            String solrCollection = selectedJson.getString(SOLR_COLLECTION);
            ensureNotError(doCheckSolrCollection(solrCollection), SOLR_COLLECTION);
            setSolrCollection(solrCollection);
        }
        if (selectedJson.containsKey(LUCENE_PATH)) {
            String lucenePath = selectedJson.getString(LUCENE_PATH);
            ensureNotError(doCheckLucenePath(lucenePath), LUCENE_PATH);
            setLucenePath(new File(lucenePath));
        }
        setSearchBackend(SearchBackendEngine.valueOf(json.get("").toString()));
        backendManager.reconfigure(searchBackend, getConfig());
        save();
        return super.configure(req, json);
    }

    private void ensureNotError(FormValidation formValidation, String field) throws FormException {
        if (formValidation.kind == FormValidation.Kind.ERROR) {
            throw new FormException("Incorrect search config field: " + field, field);
        }
    }

    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("solrUrl", solrUrl);
        config.put("lucenePath", lucenePath);
        config.put("solrCollection", solrCollection);
        return config;
    }

    public String getSolrCollection() {
        return solrCollection;
    }

    public void setSolrCollection(String solrCollection) {
        this.solrCollection = solrCollection;
    }
}
