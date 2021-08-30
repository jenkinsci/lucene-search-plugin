package org.jenkinsci.plugins.lucene.search.databackend;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Run;
import hudson.search.SearchResult;
import hudson.search.SuggestedItem;

import java.io.IOException;
import java.util.*;

import javax.inject.Inject;

import jenkins.model.Jenkins;

import org.apache.log4j.Logger;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;
import org.jenkinsci.plugins.lucene.search.SearchResultImpl;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendConfiguration;

@Extension
public class SearchBackendManager {
    private static final Logger LOG = Logger.getLogger(SearchBackendManager.class);

    private transient SearchBackend<?> instance;
    private transient List<SearchFieldDefinition> cachedFieldDefinitions;

    @Inject
    private transient SearchBackendConfiguration backendConfig;

    private synchronized SearchBackend<?> getBackend() {
        if (instance == null) {
            instance = LuceneSearchBackend.create(backendConfig.getConfig());
        }
        return instance;
    }

    public synchronized void reconfigure(final Map<String, Object> config) throws IOException {
        if (instance != null) {
            instance.close();
            instance = instance.reconfigure(config);
        } else {
            instance = LuceneSearchBackend.create(backendConfig.getConfig());
        }
    }

    public List<FreeTextSearchItemImplementation> getHits(String query, boolean searchNext) {
        List<FreeTextSearchItemImplementation> hits = getBackend().getHits(query, searchNext);
        if (backendConfig.isUseSecurity()) {
            Jenkins jenkins = Jenkins.getInstance();
            Iterator<FreeTextSearchItemImplementation> iter = hits.iterator();
            while (iter.hasNext()) {
                FreeTextSearchItemImplementation searchItem = iter.next();
                Item item = jenkins.getItem(searchItem.getProjectName());
                if (item == null) {
                    iter.remove();
                }
            }
        }
        return hits;
    }

    public SearchResult getSuggestedItems(String query) {
        SearchResultImpl result = new SearchResultImpl();
        for (FreeTextSearchItemImplementation item : getHits(query, false)) {
            result.add(new SuggestedItem(item));
        }
        return result;
    }

    public void clean(ManagerProgress progress) {
        progress.setMax(1);
        getBackend().cleanAllJob(progress);
    }

    public void removeBuild(Run<?, ?> run) throws IOException {
        getBackend().removeBuild(run);
    }

    public void deleteJob(String jobName) throws IOException {
        getBackend().deleteJob(jobName);
    }


    public void storeBuild(Run<?, ?> run) throws IOException {
        getBackend().storeBuild(run);
    }

    public void rebuildDatabase(ManagerProgress progress, int maxWorkers, Set<String> jobs, boolean overwrite) {
        try {
            getBackend().rebuildDatabase(progress, maxWorkers, jobs, overwrite);
        } catch (Exception e) {
            progress.completedWithErrors(e);
            LOG.error("Failed rebuilding search database", e);
        } finally {
            progress.setFinished();
        }
    }
}