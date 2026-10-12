package org.jenkinsci.plugins.lucene.search.databackend;

import com.google.common.annotations.VisibleForTesting;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.inject.Inject;
import jenkins.model.Jenkins;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.lucene.store.AlreadyClosedException;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;
import org.jenkinsci.plugins.lucene.search.SearchResultImpl;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendConfiguration;

@Extension
public class SearchBackendManager {
    private static final Logger LOG = Logger.getLogger(SearchBackendManager.class.getName());

    /** A backend that cannot be created is retried on every operation; log it at most this often. */
    private static final long UNAVAILABLE_LOG_INTERVAL_MS = 60_000L;

    private transient SearchBackend<?> instance;

    @Inject
    private transient SearchBackendConfiguration backendConfig;

    private final ReentrantReadWriteLock backendLock = new ReentrantReadWriteLock();

    /** How often a dead backend has been discarded so far (log messages only). */
    private final AtomicInteger backendRebuilds = new AtomicInteger();

    private final AtomicLong lastUnavailableLog = new AtomicLong();

    /** An operation to run against the backend, returning a result. */
    @FunctionalInterface
    private interface BackendAction<T> {
        T run(SearchBackend<?> backend) throws IOException;
    }

    /** An operation to run against the backend that produces no result. */
    @FunctionalInterface
    private interface BackendWrite {
        void run(SearchBackend<?> backend) throws IOException;
    }

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
            logUnavailable("Search backend is unavailable; cannot " + operation);
            backendLock.readLock().unlock();
        }
        return instance;
    }

    private void unlockBackend() {
        backendLock.readLock().unlock();
    }

    /**
     * Logs an unavailable backend at most once per interval. Without this a
     * backend that cannot be created logs on every operation — a build.xml save
     * alone fires once every second or two, which is how a broken index produced
     * a multi-thousand-line log storm in production.
     */
    private void logUnavailable(String message) {
        long now = System.currentTimeMillis();
        long last = lastUnavailableLog.get();
        if (now - last >= UNAVAILABLE_LOG_INTERVAL_MS && lastUnavailableLog.compareAndSet(last, now)) {
            LOG.warning(message + " (further occurrences suppressed for "
                    + (UNAVAILABLE_LOG_INTERVAL_MS / 1000) + "s)");
        }
    }

    /**
     * Runs an action against the current backend, healing a dead writer: the
     * backend is discarded, rebuilt and the action retried once. Retrying is safe
     * because every routed operation is idempotent — storeBuild is an
     * updateDocument upsert, the deletes are term deletes, getHits is a read.
     *
     * <p>An <em>unavailable</em> backend (none could be created) returns
     * {@code unavailableFallback}: that is the long-standing "skip the operation"
     * behaviour, logged at WARNING by {@link #lockBackend}. A backend that dies
     * <em>after</em> being rebuilt is different — something is wrong that a
     * rebuild cannot fix — so it is reported as an IOException rather than
     * silently dropping the write.
     *
     * @return the action's result, or {@code unavailableFallback} if there was no
     *         backend to run against
     */
    private <T> T withBackend(String operation, T unavailableFallback, BackendAction<T> action) throws IOException {
        boolean rebuilt = false;
        while (true) {
            SearchBackend<?> backend = lockBackend(operation);
            if (backend == null) {
                return unavailableFallback;
            }
            boolean died = false;
            try {
                return action.run(backend);
            } catch (AlreadyClosedException e) {
                died = true;
                if (rebuilt) {
                    LOG.log(Level.SEVERE, "Search backend is still dead after being rebuilt: " + operation, e);
                    throw new IOException("Lucene search backend is unavailable: " + operation, e);
                }
                LOG.log(
                        Level.SEVERE,
                        "Search backend died (IndexWriter closed); rebuilding it and retrying: " + operation,
                        e);
            } finally {
                unlockBackend();
                if (died) {
                    discardBackend(backend);
                }
            }
            rebuilt = true; // only reached after the first death: retry once on the rebuilt backend
        }
    }

    /**
     * Read-path variant: a backend that cannot be rebuilt degrades to
     * {@code fallback} instead of throwing, so searches return no results rather
     * than failing the page.
     */
    private <T> T withBackendOrFallback(String operation, T fallback, BackendAction<T> action) {
        try {
            return withBackend(operation, fallback, action);
        } catch (IOException e) {
            // Already logged at SEVERE by withBackend.
            return fallback;
        }
    }

    /**
     * Write-path variant. Returns {@code false} when there is no backend to write
     * to, which the caller reports as a skipped operation (unchanged behaviour);
     * an IOException means the backend died and could not be rebuilt.
     */
    private boolean withBackendWrite(String operation, BackendWrite write) throws IOException {
        return Boolean.TRUE.equals(withBackend(operation, Boolean.FALSE, backend -> {
            write.run(backend);
            return Boolean.TRUE;
        }));
    }

    /**
     * Forgets a backend whose IndexWriter died and closes it, so the next
     * operation builds a fresh one. Only the manager closes writers, and only
     * under the write lock: closing a live writer is what killed the writer in
     * the production incident. Closing here also releases the index path, so it
     * has to happen before anything tries to rebuild.
     */
    private void discardBackend(SearchBackend<?> dead) {
        boolean discarded = false;
        backendLock.writeLock().lock();
        try {
            if (instance == dead) {
                // Null it out first: no other thread may pick up a closed backend.
                instance = null;
                discarded = true;
            }
            // Unusable by definition (its writer is closed), so close it even if it
            // is no longer the current instance; close() is idempotent.
            dead.close();
        } finally {
            backendLock.writeLock().unlock();
        }
        if (discarded) {
            LOG.warning("Search backend discarded and will be rebuilt ("
                    + backendRebuilds.incrementAndGet() + " time(s) since startup)");
        }
    }

    /** The live backend, for tests. Production code goes through the manager. */
    @VisibleForTesting
    SearchBackend<?> currentBackend() {
        backendLock.readLock().lock();
        try {
            return instance;
        } finally {
            backendLock.readLock().unlock();
        }
    }

    /**
     * Replaces the backend with one built from {@code config}. The manager owns
     * the swap: it nulls the field out first so nothing can pick up a backend
     * that is about to be closed, closes the old one, and only then creates the
     * replacement (the new writer cannot take the index lock while the old one
     * still holds it).
     */
    public void reconfigure(final Map<String, Object> config) throws IOException {
        backendLock.writeLock().lock();
        try {
            SearchBackend<?> old = instance;
            instance = null;
            if (old != null) {
                old.close();
            }
            instance = LuceneSearchBackend.create(config);
            if (instance == null) {
                LOG.warning("Search backend reconfigure failed: backend is null");
            }
        } finally {
            backendLock.writeLock().unlock();
        }
    }

    public List<FreeTextSearchItemImplementation> getHits(String query, boolean searchNext) {
        return withBackendOrFallback(
                "get hits", Collections.emptyList(), backend -> getHits(backend, query, searchNext));
    }

    private List<FreeTextSearchItemImplementation> getHits(
            SearchBackend<?> backend, String query, boolean searchNext) {
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
        LOG.fine("removeBuild: build=" + run.getFullDisplayName());
        String operation = "remove build " + run.getFullDisplayName();
        boolean removed = withBackendWrite(operation, backend -> {
            boolean existed = backend.findRunIndex(run);
            LOG.fine("removeBuild: existed=" + existed + " build=" + run.getFullDisplayName());
            backend.removeBuild(run);
            backend.commitWrites();
            LOG.fine("removeBuild: committed OK build=" + run.getFullDisplayName());
        });
        if (!removed) {
            LOG.fine("removeBuild: backend is null, SKIPPING build=" + run.getFullDisplayName());
        }
    }

    public void deleteJob(String jobName) throws IOException {
        LOG.fine("deleteJob: job=" + jobName);
        String operation = "delete job " + jobName;
        boolean deleted = withBackendWrite(operation, backend -> {
            backend.deleteJob(jobName);
            backend.commitWrites();
            LOG.fine("deleteJob: committed OK job=" + jobName);
        });
        if (!deleted) {
            LOG.fine("deleteJob: backend is null, SKIPPING job=" + jobName);
        }
    }

    public void renameJob(String oldFullName, Job<?, ?> job) throws IOException {
        String operation = "rename job " + oldFullName;
        boolean renamed = withBackendWrite(operation, backend -> {
            backend.deleteJob(oldFullName);
            for (Run<?, ?> run : job.getBuilds()) {
                backend.storeBuild(run);
            }
            backend.commitWrites();
        });
        if (!renamed) {
            LOG.fine("renameJob: backend is null, SKIPPING job=" + oldFullName);
        }
    }

    /**
     * Returns true when the backend indexes console logs. When false,
     * onChange saves during a running build can be skipped — no indexed
     * field changes between onStarted and onCompleted.
     */
    public boolean isCollectingBuildLogs() {
        return backendConfig.isCollectBuildLogs();
    }

    public void storeBuild(Run<?, ?> run) throws IOException {
        LOG.fine("storeBuild: build=" + run.getFullDisplayName()
                + " number=" + run.getNumber()
                + " isBuilding=" + run.isBuilding()
                + " isLogUpdated=" + run.isLogUpdated()
                + " result=" + run.getResult());
        String operation = "store build " + run.getFullDisplayName();
        boolean stored = withBackendWrite(operation, backend -> {
            boolean existed = backend.findRunIndex(run);
            LOG.fine("storeBuild: existed=" + existed + " build=" + run.getFullDisplayName());
            backend.storeBuild(run);
            backend.commitWrites();
            LOG.fine("storeBuild: committed OK build=" + run.getFullDisplayName());
        });
        if (!stored) {
            LOG.fine("storeBuild: backend is null, SKIPPING build=" + run.getFullDisplayName());
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
            LOG.log(Level.SEVERE, "Failed rebuilding search database", e);
        } finally {
            progress.setFinished();
            unlockBackend();
        }
    }
}
