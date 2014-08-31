package org.jenkinsci.plugins.lucene.search.databackend;

import hudson.model.AbstractBuild;
import hudson.model.Cause;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.sf.json.JSON;
import net.sf.json.JSONObject;

import org.apache.commons.collections.map.HashedMap;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
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

import com.sun.xml.txw2.annotation.XmlElement;

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

        JSONObject fieldDefinition = new JSONObject();
        if (numeric) {
            fieldDefinition.put("type", "text_general"); // FIXME TODO
        } else {
            fieldDefinition.put("type", "text_general");
        }
        fieldDefinition.put("stored", stored);
        HttpPut httpPut = new HttpPut(httpSolrServer.getBaseURL() + "/" + solrCollection + "/schema/fields/"
                + fieldName);
        String thisIsAString = fieldDefinition.toString();
        StringEntity entity = new StringEntity(thisIsAString, ContentType.APPLICATION_JSON);
        httpPut.setEntity(entity);

        HttpResponse execute = httpSolrServer.getHttpClient().execute(httpPut);
        System.err.println(org.apache.commons.io.IOUtils.toString(execute.getEntity().getContent()));
    }

    private void definedSolrFields() throws IOException {
        for (Field f : Field.values()) {
            defineField(f.fieldName, f.numeric, f.persist);
        }
        for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {

            defineField(extension.getKeyword(), false, extension.isPersist());
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
        if (getUrl(config).toString().equals(httpSolrServer.getBaseURL())) {
            return this;
        } else {
            return new SolrSearchBackend(getUrl(config), getSolrCollection(config));
        }
    }

    @Override
    public void removeBuild(AbstractBuild<?, ?> build) {
        // TODO Auto-generated method stub

    }

}
