package org.jenkinsci.plugins.lucene.search.databackend;

import hudson.model.AbstractBuild;
import hudson.model.Job;
import hudson.model.Run;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import jenkins.model.Jenkins;

import org.jenkinsci.plugins.lucene.search.Field;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchExtension;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;

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

    public abstract void cleanDeletedBuilds(Progress progress, Job<?, ?> job);

    public abstract void cleanDeletedJobs(Progress progress);

    public abstract void deleteJob(String jobName);

    @SuppressWarnings("rawtypes")
    public void rebuildJob(Progress progress, Job<?, ?> job) throws IOException {
        for (Run<?, ?> run : job.getBuilds()) {
            if (run instanceof AbstractBuild) {
                AbstractBuild build = (AbstractBuild) run;
                removeBuild(build);
                storeBuild(build);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    public void rebuildDatabase(ManagerProgress progress) {
        List<Job> allItems = Jenkins.getInstance().getAllItems(Job.class);
        progress.setMax(allItems.size());
        try {
            cleanDeletedJobs(progress.getDeletedJobsCleanProgress());
            progress.assertNoErrors();
            for (Job job : allItems) {
                progress.setNewIteration();
                progress.setCurrentProjectName(job.getDisplayName());
                progress.setCurrent(progress.getCurrent() + 1);

                cleanDeletedBuilds(progress.getDeletedBuildsCleanProgress(), job);
                progress.assertNoErrors();
                rebuildJob(progress.getRebuildProgress(), job);
                progress.assertNoErrors();
            }
            progress.setSuccessfullyCompleted();
        } catch (IOException e) {
            progress.setError(e);
        } catch (Throwable e) {
            progress.setError(e);
        } finally {
            progress.setFinished();
        }
    }

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
