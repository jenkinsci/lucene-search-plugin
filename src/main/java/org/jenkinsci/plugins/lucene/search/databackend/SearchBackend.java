package org.jenkinsci.plugins.lucene.search.databackend;

import hudson.model.AbstractBuild;
import hudson.model.Job;
import hudson.model.Run;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import jenkins.model.Jenkins;

import org.apache.log4j.Logger;
import org.jenkinsci.plugins.lucene.search.Field;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchExtension;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;

public abstract class SearchBackend<T> {

    private static final Logger LOGGER = Logger.getLogger(SearchBackend.class);

    private class RebuildBuildWorker implements RunWithArgument<Run> {

        private final Progress progress;

        private RebuildBuildWorker(Progress progress) {
            this.progress = progress;
        }

        @Override
        public void run(Run run) {
            T oldValue = removeBuild(run);
            try {
                storeBuild(run, oldValue);
            } catch (IOException e) {
                progress.completedWithErrors(e);
                LOGGER.warn("Error rebuilding build", e);
            } finally {
                progress.incCurrent();
            }
        }
    }

    private final SearchBackendEngine engine;

    public SearchBackend(SearchBackendEngine engine) {
        this.engine = engine;
    }

    public abstract void close();

    public abstract void storeBuild(final Run<?, ?> run, T oldValue) throws IOException;

    public abstract List<FreeTextSearchItemImplementation> getHits(final String query, final boolean includeHighlights);

    public final SearchBackendEngine getEngine() {
        return engine;
    }

    public abstract SearchBackend<?> reconfigure(Map<String, Object> config);

    public abstract T removeBuild(Run<?, ?> run);

    public abstract void cleanDeletedBuilds(Progress progress, Job<?, ?> job) throws Exception;

    public abstract void cleanDeletedJobs(Progress progress) throws Exception;

    public abstract void deleteJob(String jobName);

    @SuppressWarnings("rawtypes")
    public void rebuildJob(Progress progress, Job<?, ?> job, int maxWorkers) throws IOException {
        BurstExecutor<Run> burstExecutor = BurstExecutor.create(new RebuildBuildWorker(progress), maxWorkers)
                .andStart();
        progress.setMax(0);
        for (Run<?, ?> run : job.getBuilds()) {
            if (run instanceof AbstractBuild) {
                progress.setMax(progress.getMax() + 1);
                AbstractBuild build = (AbstractBuild) run;
                burstExecutor.add(build);
            }
        }
        try {
            burstExecutor.waitForCompletion();
        } catch (InterruptedException e) {
            e.printStackTrace();
            LOGGER.warn("Why was I interrupted?", e);
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

    @SuppressWarnings("rawtypes")
    public void rebuildDatabase(ManagerProgress progress, int maxWorkers) {
        List<Job> allItems = Jenkins.getInstance().getAllItems(Job.class);
        progress.setMax(allItems.size() + 1);
        try {
            Progress cleanProgress = progress.beginCleanJob();
            cleanDeletedJobs(cleanProgress);
            progress.jobComplete();
            progress.assertNoErrors();
            for (Job job : allItems) {
                Progress currentJobProgress = progress.beginJob(job);
                try {
                    if (job.getBuilds().isEmpty()) {
                        deleteJob(job.getName());
                    } else {
                        cleanDeletedBuilds(currentJobProgress, job);
                        progress.assertNoErrors();
                        rebuildJob(currentJobProgress, job, maxWorkers);
                        progress.assertNoErrors();
                    }
                } finally {
                    progress.jobComplete();
                }
            }
            progress.setSuccessfullyCompleted();
        } catch (Exception e) {
            progress.completedWithErrors(e);
            LOGGER.error("Rebuild database failed", e);
        } finally {
            progress.setFinished();
        }
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

    public abstract List<SearchFieldDefinition> getAllFieldDefinitions() throws IOException;
}
