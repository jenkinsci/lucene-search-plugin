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
import org.apache.lucene.search.SearcherManager;
import org.jenkinsci.plugins.lucene.search.Field;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchExtension;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;
import org.jenkinsci.plugins.lucene.search.management.LuceneManager;

public abstract class SearchBackend {

    private static final Logger LOGGER = Logger.getLogger(SearchBackend.class);

    private class RebuildBuildWorker implements RunWithArgument<AbstractBuild> {

        private final Progress progress;

        private RebuildBuildWorker(Progress progress) {
            this.progress = progress;
        }

        @Override
        public void run(AbstractBuild build) {
            removeBuild(build);
            try {
                storeBuild(build);
            } catch (IOException e) {
                progress.completedWithErrors(e);
            }
        }
    }

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
    public void rebuildJob(Progress progress, Job<?, ?> job, int maxWorkers) throws IOException {
        BurstExecutor<AbstractBuild> burstExecutor = BurstExecutor.create(new RebuildBuildWorker(progress), maxWorkers)
                .andStart();
        for (Run<?, ?> run : job.getBuilds()) {
            if (run instanceof AbstractBuild) {
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

    @SuppressWarnings("rawtypes")
    public void rebuildDatabase(ManagerProgress progress, int maxWorkers) {
        List<Job> allItems = Jenkins.getInstance().getAllItems(Job.class);
        progress.setMax(allItems.size());
        try {
            cleanDeletedJobs(progress.getDeletedJobsCleanProgress());
            progress.assertNoErrors();
            for (Job job : allItems) {
                progress.setNewIteration();
                progress.next(job);
                if (job.getBuilds().isEmpty()) {
                    deleteJob(job.getName());
                } else {
                    cleanDeletedBuilds(progress.getDeletedBuildsCleanProgress(), job);
                    progress.assertNoErrors();
                    rebuildJob(progress.getRebuildProgress(), job, maxWorkers);
                    progress.assertNoErrors();
                }
                progress.setComplete();
            }
            progress.setSuccessfullyCompleted();
        } catch (IOException e) {
            e.printStackTrace();
            progress.completedWithErrors(e);
        } catch (Throwable e) {
            e.printStackTrace();
            progress.completedWithErrors(e);
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

    public abstract List<SearchFieldDefinition> getAllFieldDefinitions() throws IOException;
}
