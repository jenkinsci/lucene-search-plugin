package org.jenkinsci.plugins.lucene.search.databackend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.model.FreeStyleProject;
import hudson.model.Run;
import hudson.tasks.Shell;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
     * The constructor used to react to a lock conflict by deleting
     * {@code write.lock}. That does not release the holder's lock: it repoints the
     * path at a fresh inode, so the live writer fails its next lock validation
     * with "Underlying file changed by an external force" and dies. Opening a
     * second backend must leave the live writer's lock file alone and keep that
     * writer usable.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void shouldNotTouchTheLockFileOfALiveWriter() throws Exception {
        FreeStyleProject project = rule.createFreeStyleProject("second-writer-test");
        project.getBuildersList().add(new Shell("echo SECOND_WRITER_MARKER\n"));
        rule.buildAndAssertSuccess(project);
        Run<?, ?> run = project.getLastBuild();
        assertNotNull(run, "Build must exist");

        LuceneSearchBackend first = LuceneSearchBackend.create(configMap());
        assertNotNull(first, "First backend should be created");
        Object lockFileBefore = lockFileKey();

        // A second writer cannot be let in — and must not be forced in either.
        LuceneSearchBackend second = LuceneSearchBackend.create(configMap());
        assertNull(second, "A second backend on the same index cannot be opened");
        assertEquals(lockFileBefore, lockFileKey(), "write.lock must not be deleted and recreated");

        // The live writer must still be able to write: that is what the deleted
        // lock file used to break.
        assertDoesNotThrow(
                () -> {
                    first.storeBuild(run);
                    first.commitWrites();
                },
                "The second open attempt must not have disturbed the live writer");
        first.close();
    }

    private Object lockFileKey() throws IOException {
        Object key = Files.readAttributes(tempDir.resolve("write.lock"), BasicFileAttributes.class)
                .fileKey();
        assertNotNull(key, "This filesystem should report a file key");
        return key;
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
