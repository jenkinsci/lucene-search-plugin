package org.jenkinsci.plugins.lucene.search.databackend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.tasks.Shell;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests that {@link SearchBackendManager} gracefully handles backend
 * unavailability, that the ReadWriteLock in lockBackend/reconfigure prevents
 * {@code AlreadyClosedException} from in-flight writes, and that a backend whose
 * writer has died is discarded, rebuilt and retried instead of failing forever.
 */
@WithJenkins
class SearchBackendManagerTest {

    private JenkinsRule rule;
    private ExecutorService executor;

    @BeforeEach
    void setUp(JenkinsRule r) {
        rule = r;
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        // Several tests point lucenePath at a file that cannot be opened as an
        // index, and the Jenkins instance — including its configuration — is
        // shared by every test in this class. Restore a usable path so the
        // outcome of a test never depends on which test ran before it.
        SearchBackendConfiguration config = GlobalConfiguration.all().get(SearchBackendConfiguration.class);
        config.setLucenePath(new File(rule.jenkins.getRootDir(), "luceneIndex"));
        config.reconfigure();
    }

    // ── null-safety ──────────────────────────────────────────────

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void getHitsShouldReturnEmptyListWhenBackendIsNull() throws Exception {
        breakBackend();
        SearchBackendManager manager = getManager();

        assertDoesNotThrow(
                () -> {
                    var hits = manager.getHits("anything", false);
                    assertTrue(hits.isEmpty(), "getHits must return empty list, not null");
                },
                "getHits must not NPE when backend is unavailable");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void getSuggestedItemsShouldNotNPEWhenBackendIsNull() throws Exception {
        breakBackend();
        SearchBackendManager manager = getManager();

        assertDoesNotThrow(
                () -> manager.getSuggestedItems("anything"),
                "getSuggestedItems must not NPE when backend is unavailable");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void storeBuildShouldNotThrowWhenBackendIsNull() throws Exception {
        breakBackend();
        // storeBuild needs a real Run object, but we can't easily create one
        // without a backend. Instead verify that calling it on a null backend
        // produces a warning log rather than an NPE.
        FreeStyleProject project = rule.createFreeStyleProject("null-backend-project");
        project.getBuildersList().add(new Shell("echo test"));
        rule.buildAndAssertSuccess(project);

        // Break the backend AFTER the build exists.
        breakBackend();
        SearchBackendManager manager = getManager();

        assertDoesNotThrow(
                () -> manager.storeBuild(project.getLastBuild()),
                "storeBuild must not NPE when backend is unavailable");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void removeBuildShouldNotThrowWhenBackendIsNull() throws Exception {
        FreeStyleProject project = rule.createFreeStyleProject("null-remove-project");
        project.getBuildersList().add(new Shell("echo test"));
        rule.buildAndAssertSuccess(project);

        breakBackend();
        SearchBackendManager manager = getManager();

        assertDoesNotThrow(
                () -> manager.removeBuild(project.getLastBuild()),
                "removeBuild must not NPE when backend is unavailable");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void deleteJobShouldNotThrowWhenBackendIsNull() throws Exception {
        breakBackend();
        SearchBackendManager manager = getManager();

        assertDoesNotThrow(
                () -> manager.deleteJob("nonexistent-job"),
                "deleteJob must not NPE when backend is unavailable");
    }

    // ── ReadWriteLock: reconfigure vs in-flight reads ────────────

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void reconfigureShouldNotCauseExceptionsDuringConcurrentSearches() throws Exception {
        // Set up a valid backend first.
        SearchBackendManager manager = getManager();
        FreeStyleProject project = rule.createFreeStyleProject("concurrent-test");
        project.getBuildersList().add(new Shell("echo UNIQUE_CONCURRENT_SEARCH\n"));
        rule.buildAndAssertSuccess(project);

        // Verify the build is searchable.
        var hits = manager.getHits("UNIQUE_CONCURRENT_SEARCH", false);
        assertTrue(hits.size() > 0, "Build must be searchable before concurrency test");

        // Now hammer the backend with concurrent getHits + reconfigure calls.
        // If the ReadWriteLock is working correctly, reconfigure will wait for
        // in-flight getHits to finish before closing the old backend.
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        int readerThreads = 3;
        CountDownLatch doneLatch = new CountDownLatch(readerThreads + 1);

        // Reader threads: call getHits in a tight loop.
        for (int i = 0; i < readerThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 50; j++) {
                        manager.getHits("UNIQUE_CONCURRENT_SEARCH", false);
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    failure.compareAndSet(null, e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Writer thread: reconfigure repeatedly.
        executor.submit(() -> {
            try {
                startLatch.await();
                SearchBackendConfiguration config =
                        GlobalConfiguration.all().get(SearchBackendConfiguration.class);
                for (int j = 0; j < 5; j++) {
                    config.reconfigure();
                    Thread.sleep(20);
                }
            } catch (Exception e) {
                failure.compareAndSet(null, e);
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(25, TimeUnit.SECONDS), "All threads must complete");

        if (failure.get() != null) {
            throw new AssertionError(
                    "Concurrent reconfigure + getHits must not throw: " + failure.get().getMessage(),
                    failure.get());
        }
    }

    // ── dead writer: discard, rebuild, retry ─────────────────────

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void storeBuildShouldRebuildADeadBackendAndRetry() throws Exception {
        SearchBackendManager manager = getManager();
        FreeStyleProject project = rule.createFreeStyleProject("heal-test");
        project.getBuildersList().add(new Shell("echo HEAL_RETRY_MARKER\n"));
        rule.buildAndAssertSuccess(project);
        Run<?, ?> run = project.getLastBuild();
        assertNotNull(run, "Build must exist");
        assertTrue(
                manager.getHits("HEAL_RETRY_MARKER", false).size() > 0,
                "Build must be searchable before the writer is killed");

        // Kill the live writer the way production did: the shared backend was
        // closed while the plugin kept writing through it.
        SearchBackend<?> killed = manager.currentBackend();
        assertNotNull(killed, "A backend must be live");
        killed.close();

        // The write must be detected, the dead backend discarded and rebuilt, and
        // the write retried — with nothing escaping into the calling listener.
        assertDoesNotThrow(() -> manager.storeBuild(run), "storeBuild must heal a dead backend and retry");
        assertNotSame(killed, manager.currentBackend(), "The dead backend must have been replaced");
        assertTrue(
                manager.getHits("HEAL_RETRY_MARKER", false).size() > 0,
                "Build must be searchable again after the rebuild");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void storeBuildShouldSkipWhenNoBackendCanBeRebuilt() throws Exception {
        SearchBackendManager manager = getManager();
        FreeStyleProject project = rule.createFreeStyleProject("skip-test");
        project.getBuildersList().add(new Shell("echo SKIP_MARKER\n"));
        rule.buildAndAssertSuccess(project);
        Run<?, ?> run = project.getLastBuild();
        assertNotNull(run, "Build must exist");

        SearchBackend<?> killed = manager.currentBackend();
        assertNotNull(killed, "A backend must be live");
        killed.close();
        // Make rebuilding impossible: the configured path is a regular file.
        breakBackend();

        // storeBuild runs on every build.xml save, so a backend that cannot be
        // created must skip the write rather than throw on every save. A backend
        // that dies *after* a rebuild is the loud case and throws IOException.
        assertDoesNotThrow(() -> manager.storeBuild(run), "A backend that cannot be rebuilt must skip, not throw");
        assertNull(manager.currentBackend(), "No backend can exist for an unopenable path");
    }

    // ── shutdown: commit and release the lock before the JVM goes ─

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void closeBackendShouldCommitAndReleaseTheIndex() throws Exception {
        SearchBackendManager manager = getManager();
        FreeStyleProject project = rule.createFreeStyleProject("shutdown-test");
        project.getBuildersList().add(new Shell("echo SHUTDOWN_MARKER\n"));
        rule.buildAndAssertSuccess(project);
        Run<?, ?> run = project.getLastBuild();
        assertNotNull(run, "Build must exist");

        // What the shutdown hook does before the JVM exits: close the backend so
        // the writer commits and the index lock is released.
        manager.closeBackend();
        assertNull(manager.currentBackend(), "The backend must be closed and forgotten");

        // The index must be left readable and the path free for the next start:
        // a new backend has to be able to open it.
        assertTrue(
                manager.getHits("SHUTDOWN_MARKER", false).size() > 0,
                "The committed index must still be readable after the backend was closed");
        assertNotNull(manager.currentBackend(), "A later operation must build a fresh backend");
    }

    // ── helpers ──────────────────────────────────────────────────

    private SearchBackendManager getManager() {
        return Jenkins.get().getExtensionList(SearchBackendManager.class).get(0);
    }

    /**
     * Reconfigures the backend to use a path that cannot be opened as a Lucene
     * index directory (a regular file instead of a directory). This causes
     * {@code LuceneSearchBackend.create()} to return null, simulating an
     * unavailable backend.
     */
    private void breakBackend() throws Exception {
        SearchBackendConfiguration config =
                GlobalConfiguration.all().get(SearchBackendConfiguration.class);

        // Create a regular file — FSDirectory.open() will fail on it.
        File invalidPath = new File(rule.jenkins.getRootDir(), "invalid-lucene-path");
        invalidPath.createNewFile();

        config.setLucenePath(invalidPath);
        config.reconfigure();
    }
}
