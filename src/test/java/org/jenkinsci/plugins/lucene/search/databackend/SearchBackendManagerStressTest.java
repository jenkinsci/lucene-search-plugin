package org.jenkinsci.plugins.lucene.search.databackend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.tasks.Shell;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Concurrent readers and writers hammering the index while the ways the writer
 * can die are injected underneath them: something closing the live writer (the
 * production failure), a configuration save (close and rebuild), the shutdown
 * path, and a reload-style second backend taking the index over.
 *
 * <p>What it pins down is not throughput but two invariants that the production
 * incident violated: a dead writer is healed rather than being fatal (only
 * IOException may surface, never a bare AlreadyClosedException, and reads never
 * fail), and the index converges — everything written is readable once the storm
 * stops. It also asserts the injections actually exercised the heal path, so the
 * test cannot pass vacuously, and that no writer is left behind.
 *
 * <p>The storm runs for {@value #DEFAULT_SECONDS} seconds by default; pass
 * {@code -Dlucene.stress.seconds=300} for a soak run.
 */
@WithJenkins
class SearchBackendManagerStressTest {

    private static final Logger LOG = Logger.getLogger(SearchBackendManagerStressTest.class.getName());

    private static final int DEFAULT_SECONDS = 12;
    private static final int READERS = 3;
    private static final int WRITERS = 2;
    private static final int JOBS = 2;
    private static final int BUILDS_PER_JOB = 2;

    private JenkinsRule rule;
    private Path indexDir;
    private ExecutorService pool;
    private final List<Run<?, ?>> runs = Collections.synchronizedList(new ArrayList<>());
    private final List<String> markers = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger injections = new AtomicInteger();
    private final AtomicInteger ioFailures = new AtomicInteger();
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();

    @BeforeEach
    void setUp(JenkinsRule r) throws Exception {
        rule = r;
        indexDir = Files.createTempDirectory("lucene-stress-");
        pool = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "lucene-stress");
            thread.setDaemon(true);
            return thread;
        });

        SearchBackendConfiguration config = config();
        config.setLucenePath(indexDir.toFile());
        config.setCollectBuildLogs(true);
        config.setUseSecurity(false);
        config.reconfigure();

        for (int job = 0; job < JOBS; job++) {
            FreeStyleProject project = rule.createFreeStyleProject("stress-job-" + job);
            for (int build = 0; build < BUILDS_PER_JOB; build++) {
                String marker = "STRESS_" + job + "_" + build + "_"
                        + UUID.randomUUID().toString().replace("-", "");
                project.getBuildersList().clear();
                project.getBuildersList().add(new Shell("echo " + marker + "\n"));
                rule.buildAndAssertSuccess(project);
                runs.add(project.getLastBuild());
                markers.add(marker);
            }
        }
        assertTrue(runs.size() >= 2, "Fixture must have indexed builds to hammer");
    }

    @AfterEach
    void tearDown() throws Exception {
        getManager().closeBackend();
        pool.shutdownNow();
        // Leave a usable index path behind: the configuration is shared by every
        // test in this class.
        SearchBackendConfiguration config = config();
        config.setLucenePath(new File(rule.jenkins.getRootDir(), "luceneIndex"));
        config.reconfigure();
        FileUtils.deleteDirectory(indexDir.toFile());
    }

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void readersAndWritersSurviveInjectedWriterFailures() throws Exception {
        SearchBackendManager manager = getManager();
        int seconds = Integer.getInteger("lucene.stress.seconds", DEFAULT_SECONDS);

        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        running.set(true);
        paused.set(false);

        for (int i = 0; i < READERS; i++) {
            tasks.add(pool.submit(() -> {
                await(start);
                while (running.get()) {
                    waitWhilePaused();
                    inFlight.incrementAndGet();
                    try {
                        manager.getHits(randomMarker(), false);
                        reads.incrementAndGet();
                    } catch (Throwable t) {
                        // A read must never fail: the manager heals and falls back.
                        fail(unexpected, "reader", t);
                        return;
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    // Yield between reads, otherwise the readers hold the shared lock
                    // in a tight loop and starve the injector's exclusive operations.
                    sleep(1);
                }
            }));
        }

        for (int i = 0; i < WRITERS; i++) {
            tasks.add(pool.submit(() -> {
                await(start);
                while (running.get()) {
                    waitWhilePaused();
                    Run<?, ?> run = randomRun();
                    inFlight.incrementAndGet();
                    try {
                        if (ThreadLocalRandom.current().nextInt(8) == 0) {
                            manager.removeBuild(run);
                        } else {
                            manager.storeBuild(run);
                        }
                        writes.incrementAndGet();
                    } catch (IOException e) {
                        // Allowed: the backend died again before the retry could
                        // rebuild it. What must not happen is a raw
                        // AlreadyClosedException escaping, or a silent drop.
                        ioFailures.incrementAndGet();
                    } catch (Throwable t) {
                        fail(unexpected, "writer", t);
                        return;
                    } finally {
                        inFlight.decrementAndGet();
                    }
                    sleep(1);
                }
            }));
        }

        tasks.add(pool.submit(() -> {
            await(start);
            while (running.get()) {
                sleep(200);
                if (!running.get()) {
                    break;
                }
                // Hold the workers off and let in-flight operations finish before
                // injecting: an injected close that lands between prepareCommit and
                // commit makes Lucene refuse to close and keep the write lock, which
                // is a state the plugin's own serialised closes cannot produce.
                paused.set(true);
                try {
                    if (drain()) {
                        inject(manager);
                    }
                } catch (Throwable t) {
                    fail(unexpected, "injector", t);
                    return;
                } finally {
                    paused.set(false);
                }
            }
        }));

        start.countDown();
        Thread.sleep(seconds * 1000L);
        running.set(false);
        for (Future<?> task : tasks) {
            task.get(60, TimeUnit.SECONDS);
        }

        LOG.info("stress: " + reads.get() + " reads, " + writes.get() + " writes, " + injections.get()
                + " injections, " + ioFailures.get() + " IOException(s) from writers, " + manager.rebuildCount()
                + " backend rebuild(s)");

        assertNull(unexpected.get(), () -> "Unexpected failure: " + unexpected.get());
        assertTrue(reads.get() > 0 && writes.get() > 0, "The storm must actually have run");
        assertTrue(
                injections.get() >= 10,
                "The storm must inject failures at a useful rate rather than being starved, injected: "
                        + injections.get());
        assertTrue(
                manager.rebuildCount() > 0,
                "The injected failures must have exercised the heal path, otherwise this test proves nothing");

        // Convergence: with the storm stopped, everything written must be
        // readable, and no writer may be left open behind the manager's back.
        for (Run<?, ?> run : runs) {
            manager.storeBuild(run);
        }
        SearchBackend<?> live = manager.currentBackend();
        assertNotNull(live, "A backend must be live once the storm has stopped");
        for (Run<?, ?> run : runs) {
            assertTrue(live.findRunIndex(run), "Every build must be indexed again after the storm: " + run);
        }
        for (String marker : markers) {
            assertTrue(
                    manager.getHits(marker, false).size() > 0,
                    "Every build must be searchable again after the storm: " + marker);
        }
        assertEquals(1, LuceneSearchBackend.openIndexCount(), "Exactly one writer may own the index");

        manager.closeBackend();
        assertEquals(0, LuceneSearchBackend.openIndexCount(), "Closing the backend must release the index");
    }

    /**
     * Injects the ways the plugin loses its writer, called with no operation in
     * flight (see {@link #drain()}).
     *
     * <p>Not injected: a second backend opened out of band while the manager is
     * live. No plugin path does that, and it produced two genuine concurrent
     * writers — Lucene failed with "checksum passed ... possibly transient resource
     * issue" on a segments file rewritten underneath it. The reload-style takeover
     * has its own deterministic test instead.
     */
    private void inject(SearchBackendManager manager) throws Exception {
        switch (injections.incrementAndGet() % 3) {
            case 0:
                // The writer dies under the workers: they all find it dead at once and
                // have to heal it between themselves. This is the production failure.
                SearchBackend<?> live = manager.currentBackend();
                if (live != null) {
                    live.close();
                }
                break;
            case 1:
                // The shutdown path.
                manager.closeBackend();
                break;
            default:
                // An admin saved the global configuration.
                config().reconfigure();
                break;
        }
    }

    /** Waits for in-flight operations to finish so an injection cannot land mid-commit. */
    private boolean drain() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            sleep(1);
        }
        return inFlight.get() == 0;
    }

    private void waitWhilePaused() {
        while (paused.get() && running.get()) {
            sleep(1);
        }
    }

    private void fail(AtomicReference<Throwable> unexpected, String who, Throwable t) {
        LOG.log(Level.SEVERE, who + " failed unexpectedly", t);
        unexpected.compareAndSet(null, t);
    }

    private static void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private SearchBackendManager getManager() {
        return Jenkins.get().getExtensionList(SearchBackendManager.class).get(0);
    }

    private SearchBackendConfiguration config() {
        return GlobalConfiguration.all().get(SearchBackendConfiguration.class);
    }

    private Run<?, ?> randomRun() {
        return runs.get(ThreadLocalRandom.current().nextInt(runs.size()));
    }

    private String randomMarker() {
        return markers.get(ThreadLocalRandom.current().nextInt(markers.size()));
    }
}
