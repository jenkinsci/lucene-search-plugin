package org.jenkinsci.plugins.lucene.search.config;

import hudson.Extension;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Collections;
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
import org.apache.http.impl.client.ContentEncodingHttpClient;
import org.apache.http.impl.client.DecompressingHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
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
    SearchBackendManager backendManager;

    private URI solrUrl = URI.create("http://127.0.0.1:8983/");
    private File lucenePath = new File(Jenkins.getInstance().getRootDir(), "luceneIndex");
    private String solrCollection = "lucene-search-plugin";
    private SearchBackendEngine searchBackend = SearchBackendEngine.LUCENE;

    @DataBoundConstructor
    public SearchBackendConfiguration(final String solrUrl, final String lucenePath, final String searchBackend, final String solrCollection) {
        this(URI.create(solrUrl), new File(lucenePath), SearchBackendEngine.valueOf(searchBackend), solrCollection);
    }

    public SearchBackendConfiguration(final URI solrUrl, final File lucenePath, final SearchBackendEngine searchBackend, final String solrCollection) {
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
            return FormValidation.ok("Found at " + uri);
        } catch (IOException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    @Override
    public boolean configure(final StaplerRequest req, final JSONObject json)
            throws hudson.model.Descriptor.FormException {
        JSONObject selectedJson = json.getJSONObject("searchBackend");
        if (selectedJson.containsKey(SOLR_URL)) {
            try {
                setSolrUrl(makeSolrUrl(selectedJson.getString(SOLR_URL)));
            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            }
        }
		if (selectedJson.containsKey(SOLR_COLLECTION)) {
			setSolrCollection(selectedJson.getString(SOLR_COLLECTION));
		}
        if (selectedJson.containsKey(LUCENE_PATH)) {
            setLucenePath(new File(selectedJson.getString(LUCENE_PATH)));
        }
        setSearchBackend(SearchBackendEngine.valueOf(json.get("").toString()));
        save();
        backendManager.reconfigure(searchBackend, getConfig());
        return super.configure(req, json);
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
