package org.jenkinsci.plugins.lucene.search.databackend;

import hudson.model.AbstractBuild;
import hudson.model.BallColor;
import hudson.model.Cause;
import hudson.model.Job;
import jenkins.model.Jenkins;
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
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrServer;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.jenkinsci.plugins.lucene.search.Field;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchExtension;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.jenkinsci.plugins.lucene.search.Field.BALL_COLOR;
import static org.jenkinsci.plugins.lucene.search.Field.BUILD_NUMBER;
import static org.jenkinsci.plugins.lucene.search.Field.BUILT_ON;
import static org.jenkinsci.plugins.lucene.search.Field.CONSOLE;
import static org.jenkinsci.plugins.lucene.search.Field.DURATION;
import static org.jenkinsci.plugins.lucene.search.Field.ID;
import static org.jenkinsci.plugins.lucene.search.Field.PROJECT_DISPLAY_NAME;
import static org.jenkinsci.plugins.lucene.search.Field.PROJECT_NAME;
import static org.jenkinsci.plugins.lucene.search.Field.RESULT;
import static org.jenkinsci.plugins.lucene.search.Field.START_CAUSE;
import static org.jenkinsci.plugins.lucene.search.Field.START_TIME;

public class SolrSearchBackend extends SearchBackend {

    private static final Logger LOGGER = Logger.getLogger(SolrSearchBackend.class.getName());
    private static final String[] EMPTY_ARRAY = new String[0];
    public static final String COMPOSITE_SEARCH_FIELD = "text";

    private final HttpSolrServer httpSolrServer;
    private final String solrCollection;

    public SolrSearchBackend(URI url, String solrCollection) {
        super(SearchBackendEngine.SOLR);
        httpSolrServer = new HttpSolrServer(url.toString());
        this.solrCollection = solrCollection;
        try {
            definedSolrFields();
            String[] defaultSearchableFields = getAllDefaultSearchableFields();
            defineCopyField(defaultSearchableFields);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static SolrSearchBackend create(final Map<String, Object> config) {
        return new SolrSearchBackend(getUrl(config), getSolrCollection(config));
    }

    private static String getSolrCollection(Map<String, Object> config) {
        return (String) config.get("solrCollection");
    }

    private static URI getUrl(Map<String, Object> config) {
        return (URI) config.get("solrUrl");
    }

    private void defineField(String fieldName, boolean numeric, boolean stored, boolean multiValued) throws IOException {
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
            fieldDefinition.put("multiValued", multiValued);
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

    private void defineCopyField(String[] defaultSearchable) throws IOException {
        HttpClient httpClient = httpSolrServer.getHttpClient();

        String url = httpSolrServer.getBaseURL() + "/" + solrCollection + "/schema/copyfields/?wt=json";
        HttpGet httpGet = new HttpGet(url);
        HttpResponse response = httpClient.execute(httpGet);
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new IOException("Could not load copyfields: " + response.getStatusLine());
        }
        JSONObject json = getJson(response);
        boolean changedCopyField = false;
        JSONArray copyFields = json.getJSONArray("copyFields");
        for (String fieldName : defaultSearchable) {
            JSONObject copyField = null;
            for (Object o : copyFields) {
                JSONObject cf = (JSONObject) o;
                if (cf.getString("source").equals(fieldName)) {
                    copyField = cf;
                    break;
                }
            }

            if (copyField == null) {
                JSONObject newCopyField = new JSONObject();
                newCopyField.put("source", fieldName);
                JSONArray array = new JSONArray();
                array.add(COMPOSITE_SEARCH_FIELD);
                newCopyField.put("dest", array);
                copyFields.add(newCopyField);
                changedCopyField = true;
            } else {
                Object dest = copyField.get("dest");
                if (dest instanceof String) {
                    if (!dest.equals(COMPOSITE_SEARCH_FIELD)) {
                        JSONArray array = new JSONArray();
                        array.add(dest);
                        array.add(COMPOSITE_SEARCH_FIELD);
                        copyField.put("dest", array);
                        changedCopyField = true;
                    }
                } else if (dest instanceof JSONArray) {
                    if (!((JSONArray) dest).contains(COMPOSITE_SEARCH_FIELD)) {
                        ((JSONArray) dest).add(COMPOSITE_SEARCH_FIELD);
                        changedCopyField = true;
                    }
                }
            }
        }
        if (changedCopyField) {
            String jsonString = copyFields.toString(4);
            StringEntity stringEntity = new StringEntity(jsonString, ContentType.APPLICATION_JSON);
            HttpPost post = new HttpPost(httpSolrServer.getBaseURL() + "/" + solrCollection + "/schema/copyfields");
            post.setEntity(stringEntity);
            response = httpClient.execute(post);
        }
        if (response.getStatusLine().getStatusCode() != 200) {
            throw new IOException("Could not save copyfields: " + response.getStatusLine());
        }
    }

    private void definedSolrFields() throws IOException {
        defineField(COMPOSITE_SEARCH_FIELD, false, false, true);
        for (Field field : Field.values()) {
            defineField(field.fieldName, field.numeric, field.persist, false);
        }
        for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {
            defineField(extension.getKeyword(), false, extension.isPersist(), false);
        }
    }

    @Override
    public void storeBuild(AbstractBuild<?, ?> build) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        build.getLogText().writeLogTo(0, byteArrayOutputStream);
        String consoleOutput = byteArrayOutputStream.toString();

        try {
            SolrInputDocument doc = new SolrInputDocument();
            doc.addField(ID.fieldName, build.getId());
            doc.addField(PROJECT_NAME.fieldName, build.getProject().getName());
            doc.addField(PROJECT_DISPLAY_NAME.fieldName, build.getProject().getDisplayName());
            doc.addField(BUILD_NUMBER.fieldName, build.getNumber());
            doc.addField(RESULT.fieldName, build.getResult().toString());
            doc.addField(DURATION.fieldName, build.getDuration());
            doc.addField(START_TIME.fieldName, build.getStartTimeInMillis());
            doc.addField(BUILT_ON.fieldName, build.getBuiltOnStr());

            StringBuilder shortDescriptions = new StringBuilder();
            for (Cause cause : build.getCauses()) {
                shortDescriptions.append(" ").append(cause.getShortDescription());
            }
            doc.addField(START_CAUSE.fieldName, shortDescriptions.toString());
            doc.addField(BALL_COLOR.fieldName, build.getIconColor().name());
            doc.addField(CONSOLE.fieldName, consoleOutput);

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
    public List<FreeTextSearchItemImplementation> getHits(String queryString, boolean includeHighlights) {
        SolrQuery query = new SolrQuery();
        query.set("df", COMPOSITE_SEARCH_FIELD);
        query.setFields(getAllFields());
        query.setQuery(queryString);
        query.setStart(0);
        //query.set("defType", "edismax");
        if (includeHighlights) {
            query.setHighlightSnippets(5);
            query.setHighlight(true);
        }
        query.setSort("score", SolrQuery.ORDER.desc);
        query.addSort(START_TIME.fieldName, SolrQuery.ORDER.desc);
        try {
            ArrayList<FreeTextSearchItemImplementation> luceneSearchResultImpl = new ArrayList<FreeTextSearchItemImplementation>();
            QueryResponse queryResponse = httpSolrServer.query(query);
            for (SolrDocument doc : queryResponse.getResults()) {
                String[] bestFragments = EMPTY_ARRAY;
                Map<String, List<String>> highlighting = queryResponse.getHighlighting().get(ID.fieldName);
                if (highlighting != null) {
                    List<String> frags = highlighting.get(CONSOLE.fieldName);
                    if (frags != null) {
                        bestFragments = frags.toArray(new String[frags.size()]);
                    }
                }
                BallColor buildIcon = BallColor.GREY;
                String colorName = (String) doc.get(BALL_COLOR.fieldName);
                if (colorName != null) {
                    buildIcon = BallColor.valueOf(colorName);
                }
                String projectName = (String) doc.get(PROJECT_NAME.fieldName);
                String buildNumber = doc.get(BUILD_NUMBER.fieldName).toString();
                luceneSearchResultImpl.add(new FreeTextSearchItemImplementation(projectName, buildNumber,
                        bestFragments, buildIcon.getImage()));
            }
            return luceneSearchResultImpl;
        } catch (SolrServerException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SearchBackend reconfigure(Map<String, Object> config) {
        if (getUrl(config).toString().equals(httpSolrServer.getBaseURL())
                && solrCollection.equals(getSolrCollection(config))) {
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

    @SuppressWarnings("rawtypes")
    @Override
    public void cleanDeletedBuilds(Progress progress, Job job) {
        int firstBuildNumber = job.getFirstBuild().getNumber();
        List<String> idsToDelete = new ArrayList<String>();
        try {
            String queryString = String.format("%s:\"%s\"", PROJECT_NAME.fieldName, job.getName());
            SolrQuery query = new SolrQuery(queryString);
            query.setFields(PROJECT_NAME.fieldName, BUILD_NUMBER.fieldName, ID.fieldName);
            query.setStart(0);
            query.setRows(99999999);
            QueryResponse queryResponse = httpSolrServer.query(query);
            progress.setMax(queryResponse.getResults().size());
            int i = 0;
            for (SolrDocument doc : queryResponse.getResults()) {
                progress.setCurrent(i);
                Integer buildNumber = (Integer) doc.get(BUILD_NUMBER.fieldName);
                if (firstBuildNumber > buildNumber) {
                    idsToDelete.add((String) doc.get(ID.fieldName));
                }
                i++;
            }
            httpSolrServer.deleteById(idsToDelete);
            progress.setSuccessfullyCompleted();
        } catch (SolrServerException e) {
            progress.setError(e);
        } catch (IOException e) {
            progress.setError(e);
        } finally {
            progress.setFinished();
        }
    }

    @Override
    public void deleteJob(String jobName) {
        String queryString = String.format("%s:\"%s\"", PROJECT_NAME.fieldName, jobName);
        try {
            httpSolrServer.deleteByQuery(queryString);
        } catch (SolrServerException e) {
            LOGGER.warning("Could not delete job: " + jobName + " from Solr: " + e);
        } catch (IOException e) {
            LOGGER.warning("Could not delete job: " + jobName + " from Solr: " + e);
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void cleanDeletedJobs(Progress progress) {
        try {
            Set<String> jobNames = new HashSet<String>();
            for (Job job : Jenkins.getInstance().getAllItems(Job.class)) {
                jobNames.add(job.getName());
            }
            SolrQuery query = new SolrQuery("*:*");
            query.addFacetField(PROJECT_NAME.fieldName);
            query.setRows(0);
            QueryResponse queryResponse = httpSolrServer.query(query);
            for (FacetField ff : queryResponse.getFacetFields()) {
                String jobName = ff.getName();
                if (! jobNames.contains(jobName)) {
                    deleteJob(jobName);
                }
            }
        } catch (SolrServerException e) {
            progress.setError(e);
        } finally {
            progress.setFinished();
        }
    }

}
