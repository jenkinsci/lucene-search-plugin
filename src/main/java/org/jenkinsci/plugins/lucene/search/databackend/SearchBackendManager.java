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
import java.util.concurrent.locks.ReentrantReadWriteLock;
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

    private final ReentrantReadWriteLock backendLock = new ReentrantReadWriteLock();

    /**
     * Acquires a read lock and returns the current backend, creating it lazily
     * if needed. Always pair with {@link #unlockBackend()} in a finally block.
     *
     * @param operation description of the operation (for warning if unavailable)
     * @return the backend, or null if creation failed (warning already logged,
     *         read lock already released)
     */
    private SearchBackend<?> lockBackend(String operation) {
        backendLock.readLock().lock();
        if (instance == null) {
            // Lazy creation needs the write lock — upgrade
            backendLock.readLock().unlock();
            backendLock.writeLock().lock();
            try {
                if (instance == null) {
                    instance = LuceneSearchBackend.create(backendConfig.getConfig());
                }
                // Downgrade: reacquire read before releasing write
                backendLock.readLock().lock();
            } finally {
                backendLock.writeLock().unlock();
            }
        }
        if (instance == null) {
            LOG.warn("Search backend is unavailable; cannot " + operation);
            backendLock.readLock().unlock();
        }
        return instance;
    }

    private void unlockBackend() {
        backendLock.readLock().unlock();
    }

    public void reconfigure(final Map<String, Object> config) throws IOException {
        backendLock.writeLock().lock();
        try {
            if (instance != null) {
                instance.close();
                instance = instance.reconfigure(config);
            } else {
                instance = LuceneSearchBackend.create(backendConfig.getConfig());
            }
            if (instance == null) {
                LOG.warn("Search backend reconfigure failed: backend is null");
            }
        } finally {
            backendLock.writeLock().unlock();
        }
    }

    public List<FreeTextSearchItemImplementation> getHits(String query, boolean searchNext) {
        SearchBackend<?> backend = lockBackend("get hits");
        if (backend == null) {
            return Collections.emptyList();
        }
        try {
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
        } finally {
            unlockBackend();
        }
    }

    public SearchResult getSuggestedItems(String query) {
        SearchResultImpl result = new SearchResultImpl();
        for (FreeTextSearchItemImplementation item : getHits(query, false)) {
            result.add(new SuggestedItem(item));
        }
        return result;
    }

    public void clean(ManagerProgress progress) {
        SearchBackend<?> backend = lockBackend("clean index");
        if (backend == null) {
            return;
        }
        try {
            progress.setMax(1);
            backend.cleanAllJob(progress);
        } finally {
            unlockBackend();
        }
    }

    public void abort() {
        SearchBackend<?> backend = lockBackend("abort");
        if (backend == null) {
            return;
        }
        try {
            backend.abort();
        } finally {
            unlockBackend();
        }
    }

    public void removeBuild(Run<?, ?> run) throws IOException {
        SearchBackend<?> backend = lockBackend("remove build " + run.getFullDisplayName());
        if (backend == null) {
            return;
        }
        try {
            backend.removeBuild(run);
        } finally {
            unlockBackend();
        }
    }

    public void deleteJob(String jobName) throws IOException {
        SearchBackend<?> backend = lockBackend("delete job " + jobName);
        if (backend == null) {
            return;
        }
        try {
            backend.deleteJob(jobName);
        } finally {
            unlockBackend();
        }
    }

    public void renameJob(String oldFullName, Job<?, ?> job) throws IOException {
        SearchBackend<?> backend = lockBackend("rename job " + oldFullName);
        if (backend == null) {
            return;
        }
        try {
            backend.deleteJob(oldFullName);
            for (Run<?, ?> run : job.getBuilds()) {
                backend.storeBuild(run);
            }
        } finally {
            unlockBackend();
        }
    }

    public void storeBuild(Run<?, ?> run) throws IOException {
        SearchBackend<?> backend = lockBackend("store build " + run.getFullDisplayName());
        if (backend == null) {
            return;
        }
        try {
            backend.storeBuild(run);
        } finally {
            unlockBackend();
        }
    }

    public void rebuildDatabase(ManagerProgress progress, int maxWorkers, Set<String> jobs, boolean overwrite) {
        SearchBackend<?> backend = lockBackend("rebuild database");
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
            unlockBackend();
        }
    }
}
