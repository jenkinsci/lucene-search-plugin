package org.jenkinsci.plugins.lucene.search.databackend;

import hudson.model.AbstractBuild;
import hudson.model.Job;
import org.jenkinsci.plugins.lucene.search.Field;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchExtension;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public abstract class SearchBackend {

    private final SearchBackendEngine engine;

    public SearchBackend(SearchBackendEngine engine) {
        this.engine = engine;
    }

    public abstract void storeBuild(final AbstractBuild<?, ?> build) throws IOException;

    public abstract List<FreeTextSearchItemImplementation> getHits(final String query, final boolean includeHighlights);

    public final SearchBackendEngine getEngine() {
        return engine;
    }

    public abstract SearchBackend reconfigure(Map<String, Object> config);

    public abstract void removeBuild(AbstractBuild<?, ?> build);

    public abstract void cleanDeletedBuilds(Progress progress, Job job);

    public abstract void cleanDeletedJobs(Progress progress);

    public abstract void deleteJob(String jobName);

    // Caching this method might be dangerous
    protected String[] getAllDefaultSearchableFields() {
        List<String> fieldNames = new LinkedList<String>();
        for (Field field : Field.values()) {
            if (field.defaultSearchable) {
                fieldNames.add(field.fieldName);
            }
        }
        for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {
            if (extension.isDefaultSearchable()) {
                fieldNames.add(extension.getKeyword());
            }
        }
        return fieldNames.toArray(new String[fieldNames.size()]);
    }

    protected String[] getAllFields() {
        List<String> fieldNames = new LinkedList<String>();
        for (Field field : Field.values()) {
            fieldNames.add(field.fieldName);
        }
        for (FreeTextSearchExtension extension : FreeTextSearchExtension.all()) {
            fieldNames.add(extension.getKeyword());
        }
        return fieldNames.toArray(new String[fieldNames.size()]);
    }
}
