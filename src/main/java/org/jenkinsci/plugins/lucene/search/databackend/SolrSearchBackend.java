package org.jenkinsci.plugins.lucene.search.databackend;

import hudson.model.AbstractBuild;
import hudson.model.Cause;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.common.SolrInputDocument;
import org.jenkinsci.plugins.lucene.search.Field;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchExtension;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class SolrSearchBackend implements SearchBackend {

    private HttpSolrServer httpSolrServer;
    private String solrCollection;

    public static SolrSearchBackend create(final Map<String, Object> config) {
        return new SolrSearchBackend(getUrl(config), getSolrCollection(config));
    }

    private static String getSolrCollection(Map<String, Object> config) {
        return (String) config.get("solrCollection");
    }

    private static URI getUrl(Map<String, Object> config) {
        return (URI) config.get("solrUrl");
    }

    public SolrSearchBackend(URI url, String solrCollection) {
        httpSolrServer = new HttpSolrServer(url.toString());
        this.solrCollection = solrCollection;
        try {
            definedSolrFields();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void defineField(String fieldName, boolean numeric, boolean stored) throws IOException {
		HttpClient httpClient = httpSolrServer.getHttpClient();

		String url = httpSolrServer.getBaseURL() + "/" + solrCollection + "/schema/fields/" + fieldName;
		HttpGet httpGet = new HttpGet(url);
		httpGet.addHeader("Accept", ContentType.APPLICATION_JSON.getMimeType());
		HttpResponse response = httpClient.execute(httpGet);
		JSONObject json = getJson(response);
		int statusCode = response.getStatusLine().getStatusCode();
		if (statusCode == 404) {
			JSONObject fieldDefinition = new JSONObject();
			if (numeric) {
				fieldDefinition.put("type", "tlongs");
			} else {
				fieldDefinition.put("type", "text_general");
			}
			fieldDefinition.put("multiValued", false);
			fieldDefinition.put("stored", stored);
			//fieldDefinition.put("required", true); // built-on is sometimes empty
			HttpPut httpPut = new HttpPut(url);
			String thisIsAString = fieldDefinition.toString();
			StringEntity entity = new StringEntity(thisIsAString, ContentType.APPLICATION_JSON);
			httpPut.setEntity(entity);
			HttpResponse putResponse = httpClient.execute(httpPut);
			if (putResponse.getStatusLine().getStatusCode() != 200) {
				System.err.println(json);
				throw new IOException("Could not PUT url: " + url);
			}
		} else if (statusCode != 200) {
			throw new IOException("Could not GET url: " + url);
		}
    }

	private JSONObject getJson(HttpResponse response) throws IOException {
		String json = IOUtils.toString(response.getEntity().getContent());
		return JSONObject.fromObject(json);
	}

    private void definedSolrFields() throws IOException {
        List<String> defaultSearchableFieldNames = new ArrayList<String>();
        for (Field field : Field.values()) {
            defineField(field.fieldName, field.numeric, field.persist);
            if (field.defaultSearchable) {
                defaultSearchableFieldNames.add(field.fieldName);
            }
        }
        for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {
            defineField(extension.getKeyword(), false, extension.isPersist());
            if (extension.isDefaultSearchable()) {
                defaultSearchableFieldNames.add(extension.getKeyword());
            }
        }
        defineCopyField(defaultSearchableFieldNames);
    }

    private void defineCopyField(List<String> defaultSearchable) throws IOException {
        HttpClient httpClient = httpSolrServer.getHttpClient();

        String url = httpSolrServer.getBaseURL() + "/" + solrCollection + "/schema/copyfields/?wt=json";
        HttpGet httpGet = new HttpGet(url);
        HttpResponse response = httpClient.execute(httpGet);
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new IOException("Could not load copyfields: " + response.getStatusLine());
        }
        JSONObject json = getJson(response);
        boolean changedCopyField = false;
        for (String fieldName : defaultSearchable) {
            JSONObject copyField = null;
            JSONArray copyFields = json.getJSONArray("copyFields");
            for (Object o : copyFields) {
                JSONObject cf = (JSONObject) o;
                if (copyField.getString("source").equals(fieldName)) {
                    copyField = cf;
                    break;
                }
            }

            if (copyField == null) {
                JSONObject newCopyField = new JSONObject();
                newCopyField.put("source", fieldName);
                newCopyField.put("dest", "text");
                copyFields.add(newCopyField);
                changedCopyField = true;
            } else {
                Object dest = copyField.get("dest");
                if (dest instanceof String) {
                    if (!dest.equals("text")) {
                        JSONArray array = new JSONArray();
                        array.add(dest);
                        array.add("text");
                        copyField.put("dest", array);
                        changedCopyField = true;
                    }
                } else if (dest instanceof JSONArray) {
                    if (!((JSONArray) dest).contains("text")) {
                        ((JSONArray) dest).add("text");
                        changedCopyField = true;
                    }
                }
            }
        }
        if (changedCopyField) {
            String jsonString = json.toString(4);
            StringEntity stringEntity = new StringEntity(jsonString);
            HttpPost post = new HttpPost(httpSolrServer.getBaseURL() + "/" + solrCollection
                    + "/schema/copyfields/?wt=json");
            post.setEntity(stringEntity);
            response = httpClient.execute(post);
        }
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new IOException("Could not load copyfields: " + response.getStatusLine());
        }
    }

    @Override
    public void storeBuild(AbstractBuild<?, ?> build) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        build.getLogText().writeLogTo(0, byteArrayOutputStream);
        String consoleOutput = byteArrayOutputStream.toString();

        try {
            SolrInputDocument doc = new SolrInputDocument();
            doc.addField(Field.ID.fieldName, build.getId());
            doc.addField(Field.PROJECT_NAME.fieldName, build.getProject().getName());
            doc.addField(Field.PROJECT_DISPLAY_NAME.fieldName, build.getProject().getDisplayName());
            doc.addField(Field.BUILD_NUMBER.fieldName, build.getNumber());
            doc.addField(Field.RESULT.fieldName, build.getResult().toString());
            doc.addField(Field.DURATION.fieldName, build.getDuration());
            doc.addField(Field.START_TIME.fieldName, build.getStartTimeInMillis());
            doc.addField(Field.BUILT_ON.fieldName, build.getBuiltOnStr());

            StringBuilder shortDescriptions = new StringBuilder();
            for (Cause cause : build.getCauses()) {
                shortDescriptions.append(" ").append(cause.getShortDescription());
            }
            doc.addField(Field.START_CAUSE.fieldName, shortDescriptions.toString());
            doc.addField(Field.BALL_COLOR.fieldName, build.getIconColor().name());
            doc.addField(Field.CONSOLE.fieldName, consoleOutput);

            for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {
                doc.addField(extension.getKeyword(), extension.getTextResult(build));
            }
            httpSolrServer.add(doc);
            httpSolrServer.commit();
        } catch (SolrServerException e) {
            throw new IOException(e);
        }
    }

    @Override
    public List<FreeTextSearchItemImplementation> getHits(String query, boolean includeHighlights) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public SearchBackendEngine getEngine() {
        return SearchBackendEngine.SOLR;
    }

    @Override
    public SearchBackend reconfigure(Map<String, Object> config) {
        if (getUrl(config).toString().equals(httpSolrServer.getBaseURL()) && solrCollection.equals(getSolrCollection(config))) {
            return this;
        } else {
            return new SolrSearchBackend(getUrl(config), getSolrCollection(config));
        }
    }

    @Override
    public void removeBuild(AbstractBuild<?, ?> build) {
		try {
			httpSolrServer.deleteById(build.getId());
		} catch (SolrServerException e) {
			LOGGER.warning("Could not delete build from solr: " + e.getMessage());
		} catch (IOException e) {
			LOGGER.warning("Could not delete build from solr: " + e.getMessage());
		}
	}

	private final static Logger LOGGER = Logger.getLogger(SolrSearchBackend.class.getName());
}
