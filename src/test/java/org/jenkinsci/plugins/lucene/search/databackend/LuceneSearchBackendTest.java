package org.jenkinsci.plugins.lucene.search.databackend;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import hudson.Functions;
import hudson.model.FreeStyleProject;
import hudson.tasks.BatchFile;
import hudson.tasks.Shell;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jenkinsci.plugins.lucene.search.Field;
import org.jenkinsci.plugins.lucene.search.management.LuceneManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LuceneSearchBackendTest {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  private JenkinsRule rule;
  private ExecutorService backgroundWorker;
  private JenkinsSearchBackend jenkinsSearchBackend;

  @BeforeEach
  void setup(JenkinsRule r) {
    rule = r;
    backgroundWorker = Executors.newFixedThreadPool(1);
    jenkinsSearchBackend = new JenkinsSearchBackend(rule, backgroundWorker);
  }

  @AfterEach
  void tearDown() {
    backgroundWorker.shutdownNow();
  }

  @Test
  void assertAllFieldsAreMapped() {
    for (Field f : Field.values()) {
      assertTrue(LuceneSearchBackend.FIELD_TYPE_MAP.containsKey(f), "Field: " + f + " not found");
    }
  }

  @Test
  @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS)
  void givenLuceneWhenJobsWithBuildsAreExecutedThenTheyShouldBeSearchable() throws Exception {
    jenkinsSearchBackend.setLuceneBackend(false);

    assertEquals(0, jenkinsSearchBackend.search("echo").suggestions.size());
    FreeStyleProject project1 = rule.createFreeStyleProject("project1");
    project1
        .getBuildersList()
        .add(
            Functions.isWindows()
                ? new BatchFile("echo $BUILD_TAG\n")
                : new Shell("echo $BUILD_TAG\n"));
    // Building
    rule.buildAndAssertSuccess(project1);
    rule.buildAndAssertSuccess(project1);
    rule.buildAndAssertSuccess(project1);

    rule.createFreeStyleProject("project2");

    FreeStyleProject project3 = rule.createFreeStyleProject("project3");
    project3
        .getBuildersList()
        .add(
            Functions.isWindows()
                ? new BatchFile("echo $BUILD_TAG\n")
                : new Shell("echo $BUILD_TAG\n"));
    assertEquals(3, jenkinsSearchBackend.search("echo").suggestions.size());
    rebuildDatabase();
    assertEquals(3, jenkinsSearchBackend.search("echo").suggestions.size());
  }

  @Test
  @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS)
  void givenLuceneWhenIsNewItShouldSupportRebuildFromClean() throws Exception {
    jenkinsSearchBackend.setLuceneBackend(false);

    assertEquals(0, jenkinsSearchBackend.search("echo").suggestions.size());
    rebuildDatabase();
    assertEquals(0, jenkinsSearchBackend.search("echo").suggestions.size());
    FreeStyleProject project1 = rule.createFreeStyleProject("project1");
    project1
        .getBuildersList()
        .add(
            Functions.isWindows()
                ? new BatchFile("echo $BUILD_TAG\n")
                : new Shell("echo $BUILD_TAG\n"));
    // Building
    rule.buildAndAssertSuccess(project1);
    rule.buildAndAssertSuccess(project1);
    rule.buildAndAssertSuccess(project1);
    rebuildDatabase();
    assertEquals(3, jenkinsSearchBackend.search("echo").suggestions.size());
  }

  private void rebuildDatabase() throws Exception {
    URL statusUrl = new URL(rule.getURL(), "lucenesearchmanager/status");
    final URL rebuildUrl =
        new URL(rule.getURL(), "lucenesearchmanager/postRebuildDatabase?workers=5");

    Future<Throwable> databaseRebuild =
        jenkinsSearchBackend
            .getBackgroundWorker()
            .submit(
                () -> {
                  try {
                    LuceneManager.JSReturnCollection jsonObject =
                        jenkinsSearchBackend.getRebuildStatus(rebuildUrl);
                    assertEquals(0, jsonObject.getCode(), GSON.toJson(jsonObject));
                    return null;
                  } catch (Exception e) {
                    return e;
                  }
                });
    Throwable throwable = databaseRebuild.get(10, TimeUnit.SECONDS);
    assertNull(throwable);
    LuceneManager.JSReturnCollection jsonObject = jenkinsSearchBackend.getRebuildStatus(statusUrl);
    long started = System.currentTimeMillis();
    while ((jsonObject.isRunning() || jsonObject.isNeverStarted())
        && started + 10000 > System.currentTimeMillis()) {
      Thread.sleep(1000);
      jsonObject = jenkinsSearchBackend.getRebuildStatus(statusUrl);
    }
    assertFalse(jsonObject.isRunning() || jsonObject.isNeverStarted(), "Test took too long");
    assertEquals(0, jsonObject.getCode(), GSON.toJson(jsonObject));
  }
}
