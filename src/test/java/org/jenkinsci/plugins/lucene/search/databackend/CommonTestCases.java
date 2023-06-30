package org.jenkinsci.plugins.lucene.search.databackend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import hudson.model.FreeStyleProject;
import hudson.tasks.Shell;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jenkinsci.plugins.lucene.search.management.LuceneManager;
import org.jvnet.hudson.test.JenkinsRule;
import org.xml.sax.SAXException;

public class CommonTestCases {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

  public static void rebuildDatabase(
      final JenkinsSearchBackend jenkinsSearchBackend, JenkinsRule rule)
      throws IOException, SAXException, InterruptedException, ExecutionException, TimeoutException {
    URL statusUrl = new URL(rule.getURL(), "lucenesearchmanager/status");
    final URL rebuildUrl =
        new URL(rule.getURL(), "lucenesearchmanager/postRebuildDatabase?workers=5");

    Future<Throwable> databaseRebuild =
        jenkinsSearchBackend
            .getBackgroundWorker()
            .submit(
                new Callable<Throwable>() {

                  @Override
                  public Throwable call() throws Exception {
                    try {
                      LuceneManager.JSReturnCollection jsonObject =
                          jenkinsSearchBackend.getRebuildStatus(rebuildUrl);
                      assertEquals(GSON.toJson(jsonObject), 0, jsonObject.getCode());
                      return null;
                    } catch (Exception e) {
                      return e;
                    }
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
    assertFalse("Test took too long", jsonObject.isRunning() || jsonObject.isNeverStarted());
    assertEquals(GSON.toJson(jsonObject), 0, jsonObject.getCode());
  }

  public static void givenSearchWhenJobsWithBuildsAreExecutedThenTheyShouldBeSearchable(
      JenkinsSearchBackend jenkinsSearchBackend, JenkinsRule rule)
      throws IOException, ExecutionException, InterruptedException, SAXException,
          URISyntaxException, TimeoutException {
    assertEquals(0, jenkinsSearchBackend.search("echo").suggestions.size());
    FreeStyleProject project1 = rule.createFreeStyleProject("project1");
    project1.getBuildersList().add(new Shell("echo $BUILD_TAG\n"));
    // Building
    project1.scheduleBuild2(0).get();
    project1.scheduleBuild2(0).get();
    project1.scheduleBuild2(0).get();

    rule.createFreeStyleProject("project2");

    FreeStyleProject project3 = rule.createFreeStyleProject("project3");
    project3.getBuildersList().add(new Shell("cat $BUILD_TAG\n"));
    assertEquals(3, jenkinsSearchBackend.search("echo").suggestions.size());
    rebuildDatabase(jenkinsSearchBackend, rule);
    assertEquals(3, jenkinsSearchBackend.search("echo").suggestions.size());
  }

  public static void givenSearchWhenIsNewItShouldSupportRebuildFromClean(
      JenkinsSearchBackend jenkinsSearchBackend, JenkinsRule rule)
      throws IOException, ExecutionException, InterruptedException, SAXException,
          URISyntaxException {
    try {
      assertEquals(0, jenkinsSearchBackend.search("echo").suggestions.size());
      rebuildDatabase(jenkinsSearchBackend, rule);
      assertEquals(0, jenkinsSearchBackend.search("echo").suggestions.size());
      FreeStyleProject project1 = rule.createFreeStyleProject("project1");
      project1.getBuildersList().add(new Shell("echo $BUILD_TAG\n"));
      // Building
      project1.scheduleBuild2(0).get();
      project1.scheduleBuild2(0).get();
      project1.scheduleBuild2(0).get();
      rebuildDatabase(jenkinsSearchBackend, rule);
      assertEquals(3, jenkinsSearchBackend.search("echo").suggestions.size());
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }
}
