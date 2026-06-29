package org.jenkinsci.plugins.lucene.search.databackend;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Run;
import hudson.search.SearchResult;
import hudson.search.SuggestedItem;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    @Inject
    private transient SearchBackendConfiguration backendConfig;

    private synchronized SearchBackend<?> getBackend() {
        if (instance == null) {
            instance = LuceneSearchBackend.create(backendConfig.getConfig());
        }
        return instance;
    }

    private SearchBackend<?> getBackendOrWarn(String operation) {
        SearchBackend<?> backend = getBackend();
        if (backend == null) {
            LOG.warn("Search backend is unavailable; cannot " + operation);
        }
        return backend;
    }

    public synchronized void reconfigure(final Map<String, Object> config) throws IOException {
        if (instance != null) {
            instance.close();
            instance = instance.reconfigure(config);
        } else {
            instance = LuceneSearchBackend.create(backendConfig.getConfig());
        }
        if (instance == null) {
            LOG.warn("Search backend reconfigure failed: backend is null");
        }
    }

    public List<FreeTextSearchItemImplementation> getHits(String query, boolean searchNext) {
        SearchBackend<?> backend = getBackendOrWarn("get hits");
        if (backend == null) {
            return Collections.emptyList();
        }
        List<FreeTextSearchItemImplementation> hits = backend.getHits(query, searchNext);
        if (backendConfig.isUseSecurity()) {
            Jenkins jenkins = Jenkins.getInstance();
            Iterator<FreeTextSearchItemImplementation> iter = hits.iterator();
            while (iter.hasNext()) {
                FreeTextSearchItemImplementation searchItem = iter.next();
                Item item = jenkins.getItemByFullName(searchItem.getProjectName());
                if (item == null || !item.hasPermission(Item.READ)) {
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
        SearchBackend<?> backend = getBackendOrWarn("clean index");
        if (backend == null) {
            return;
        }
        progress.setMax(1);
        backend.cleanAllJob(progress);
    }

    public void abort() {
        SearchBackend<?> backend = getBackend();
        if (backend != null) {
            backend.abort();
        }
    }

    public void removeBuild(Run<?, ?> run) throws IOException {
        SearchBackend<?> backend = getBackendOrWarn("remove build " + run.getFullDisplayName());
        if (backend == null) {
            return;
        }
        backend.removeBuild(run);
    }

    public void deleteJob(String jobName) throws IOException {
        SearchBackend<?> backend = getBackendOrWarn("delete job " + jobName);
        if (backend == null) {
            return;
        }
        backend.deleteJob(jobName);
    }

    public void renameJob(String oldFullName, Job<?, ?> job) throws IOException {
        SearchBackend<?> backend = getBackendOrWarn("rename job " + oldFullName);
        if (backend == null) {
            return;
        }
        backend.deleteJob(oldFullName);
        for (Run<?, ?> run : job.getBuilds()) {
            backend.storeBuild(run);
        }
    }

    public void storeBuild(Run<?, ?> run) throws IOException {
        SearchBackend<?> backend = getBackendOrWarn("store build " + run.getFullDisplayName());
        if (backend == null) {
            return;
        }
        backend.storeBuild(run);
    }

    public void rebuildDatabase(ManagerProgress progress, int maxWorkers, Set<String> jobs, boolean overwrite) {
        SearchBackend<?> backend = getBackendOrWarn("rebuild database");
        if (backend == null) {
            return;
        }
        try {
            backend.rebuildDatabase(progress, maxWorkers, jobs, overwrite);
        } catch (Exception e) {
            progress.completedWithErrors(e);
            LOG.error("Failed rebuilding search database", e);
        } finally {
            progress.setFinished();
        }
    }
}
