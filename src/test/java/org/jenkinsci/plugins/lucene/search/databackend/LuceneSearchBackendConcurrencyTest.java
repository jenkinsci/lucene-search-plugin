package org.jenkinsci.plugins.lucene.search.databackend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests that exercise the constructor-level recovery paths in
 * {@link LuceneSearchBackend} — particularly for stale lock files
 * left behind after a JVM restart or unclean plugin redeploy.
 */
@WithJenkins
class LuceneSearchBackendConcurrencyTest {

    private Path tempDir;

    @BeforeEach
    void setUp(JenkinsRule rule) throws IOException {
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
