package org.jenkinsci.plugins.lucene.search.databackend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.Functions;
import hudson.model.FreeStyleProject;
import hudson.tasks.BatchFile;
import hudson.tasks.Builder;
import hudson.tasks.Shell;
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
 * Tests for the batch-commit semantics introduced by commitWrites().
 *
 * <p>storeBuild() only buffers documents in the IndexWriter. Only
 * commitWrites() flushes to disk and refreshes the searcher so that
 * subsequent getHits() calls see the changes. This enables the rebuild
 * path to commit once per job instead of once per build.
 */
@WithJenkins
class LuceneSearchBackendBatchTest {

    private JenkinsRule rule;
    private java.nio.file.Path tempDir;

    @BeforeEach
    void setUp(JenkinsRule r) throws Exception {
        rule = r;
        tempDir = java.nio.file.Files.createTempDirectory("lucene-batch-test-");
    }

    @AfterEach
    void tearDown() throws java.io.IOException {
        FileUtils.deleteDirectory(tempDir.toFile());
    }

    /**
     * storeBuild() buffers the document in the IndexWriter. Without
     * commitWrites(), a concurrent search via getHits() must not see it.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void storeBuildWithoutCommitShouldNotBeSearchable() throws Exception {
        LuceneSearchBackend backend = createBackend();

        FreeStyleProject project = rule.createFreeStyleProject("batch-project");
        project.getBuildersList().add(buildScript("UNIQUE_BATCH_WORD"));
        rule.buildAndAssertSuccess(project);

        backend.storeBuild(project.getLastBuild());

        var hits = backend.getHits("UNIQUE_BATCH_WORD", false);
        assertEquals(0, hits.size(),
                "Data must not be searchable before commitWrites()");
        assertDoesNotThrow(backend::close);
    }

    /**
     * storeBuild() + commitWrites() must make data searchable and
     * durable across close/reopen.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void commitWritesShouldMakeStoreBuildVisibleAndDurable() throws Exception {
        LuceneSearchBackend backend = createBackend();

        FreeStyleProject project = rule.createFreeStyleProject("commit-project");
        project.getBuildersList().add(buildScript("UNIQUE_COMMIT_WORD"));
        rule.buildAndAssertSuccess(project);

        backend.storeBuild(project.getLastBuild());
        backend.commitWrites();

        var hits = backend.getHits("UNIQUE_COMMIT_WORD", false);
        assertTrue(hits.size() > 0, "Data must be searchable after commitWrites()");

        backend.close();

        // Re-open: committed data must survive.
        LuceneSearchBackend reopened = createBackend();
        var reopenedHits = reopened.getHits("UNIQUE_COMMIT_WORD", false);
        assertTrue(reopenedHits.size() > 0, "Committed data must survive close/reopen");
        reopened.close();
    }

    /**
     * Multiple storeBuild() calls batched into one commitWrites() must all
     * become visible at once — this is the core optimization that lets
     * rebuildDatabase() commit per-job instead of per-build.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void multipleStoreBuildsBatchedInSingleCommitShouldAllBeVisible() throws Exception {
        LuceneSearchBackend backend = createBackend();

        FreeStyleProject project = rule.createFreeStyleProject("batch-multi");
        project.getBuildersList().add(buildScript("BATCH_MULTI_WORD"));
        rule.buildAndAssertSuccess(project);
        rule.buildAndAssertSuccess(project);
        rule.buildAndAssertSuccess(project);

        for (var build : project.getBuilds()) {
            backend.storeBuild(build);
        }
        backend.commitWrites();

        var hits = backend.getHits("BATCH_MULTI_WORD", false);
        assertEquals(3, hits.size(),
                "All three builds must be searchable after one commitWrites()");
        backend.close();
    }

    // ── helpers ──────────────────────────────────────────────────

    private LuceneSearchBackend createBackend() {
        Map<String, Object> config = new HashMap<>();
        config.put("lucenePath", tempDir.toFile());
        config.put("collectBuildLogs", true);
        return LuceneSearchBackend.create(config);
    }

    private static Builder buildScript(String word) {
        return Functions.isWindows()
                ? new BatchFile("echo " + word + "\n")
                : new Shell("echo " + word + "\n");
    }
}
