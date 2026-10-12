package org.jenkinsci.plugins.lucene.search.databackend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.tasks.Shell;
import org.apache.lucene.store.AlreadyClosedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests that exercise the constructor-level recovery paths in
 * {@link LuceneSearchBackend} — particularly for stale lock files
 * left behind after a JVM restart or unclean plugin redeploy.
 */
@WithJenkins
class LuceneSearchBackendConcurrencyTest {

    private JenkinsRule rule;
    private Path tempDir;

    @BeforeEach
    void setUp(JenkinsRule r) throws IOException {
        rule = r;
        tempDir = Files.createTempDirectory("lucene-concurrency-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        FileUtils.deleteDirectory(tempDir.toFile());
    }

    @Test
    void shouldCreateAndCloseBackendSuccessfully() {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("lucenePath", tempDir.toFile());
        configMap.put("collectBuildLogs", false);

        LuceneSearchBackend backend = LuceneSearchBackend.create(configMap);
        assertNotNull(backend, "Backend creation should succeed on a clean directory");
        assertDoesNotThrow(backend::close, "close() must not throw");
    }

    /**
     * A writer that is still holding the index — one leaked by an earlier plugin
     * or configuration reload — must be closed and replaced, never bypassed by
     * deleting {@code write.lock}. The old constructor did the latter: it left
     * the leaked writer holding a lock on an unlinked file, so that writer died
     * on its next write with "Underlying file changed by an external force", and
     * nothing rebuilt it.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void shouldTakeOverFromALeakedWriterWithoutTouchingTheLockFile() throws Exception {
        FreeStyleProject project = rule.createFreeStyleProject("second-writer-test");
        project.getBuildersList().add(new Shell("echo SECOND_WRITER_MARKER\n"));
        rule.buildAndAssertSuccess(project);
        Run<?, ?> run = project.getLastBuild();
        assertNotNull(run, "Build must exist");

        LuceneSearchBackend leaked = LuceneSearchBackend.create(configMap());
        assertNotNull(leaked, "First backend should be created");

        // The successor must come up and take the index over.
        LuceneSearchBackend successor = LuceneSearchBackend.create(configMap());
        assertNotNull(successor, "The index must be reopened by closing the leaked writer, not refused");

        // The leaked writer is closed, not left half-alive on a deleted lock file.
        assertThrows(
                AlreadyClosedException.class,
                () -> {
                    leaked.storeBuild(run);
                    leaked.commitWrites();
                },
                "The leaked writer must have been closed");

        // ...and the index it was holding is intact and writable by the successor.
        successor.storeBuild(run);
        successor.commitWrites();
        successor.close();

        LuceneSearchBackend reopened = LuceneSearchBackend.create(configMap());
        assertNotNull(reopened, "Index must be reopenable after close()");
        assertTrue(reopened.findRunIndex(run), "What the successor stored must be in the index");
        reopened.close();
    }

    @Test
    void shouldCloseIdempotentlyAndReleaseTheIndexPath() {
        LuceneSearchBackend backend = LuceneSearchBackend.create(configMap());
        assertNotNull(backend, "Backend creation should succeed on a clean directory");

        // The manager may close an already closed backend (reconfigure, then a
        // rebuild), so closing twice must be harmless.
        assertDoesNotThrow(
                () -> {
                    backend.close();
                    backend.close();
                },
                "close() must be idempotent");

        // Closing must also release the index path, so the index can be reopened.
        LuceneSearchBackend reopened = LuceneSearchBackend.create(configMap());
        assertNotNull(reopened, "Index must be reopenable after close()");
        reopened.close();
    }

    private Map<String, Object> configMap() {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("lucenePath", tempDir.toFile());
        configMap.put("collectBuildLogs", false);
        return configMap;
    }

    @Test
    void shouldReopenExistingIndexWithoutError() {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("lucenePath", tempDir.toFile());
        configMap.put("collectBuildLogs", false);

        // Create and close first backend.
        LuceneSearchBackend first = LuceneSearchBackend.create(configMap);
        assertNotNull(first, "First backend should be created");
        first.close();

        // Reopen — this exercises the path where the index already exists
        // on disk. The constructor must be able to open an existing index.
        LuceneSearchBackend second = LuceneSearchBackend.create(configMap);
        assertNotNull(second, "Should reopen an existing index successfully");
        second.close();
    }
}
