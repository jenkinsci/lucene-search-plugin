package org.jenkinsci.plugins.lucene.search.databackend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

import org.jenkinsci.plugins.lucene.search.management.LuceneManager;
import org.jvnet.hudson.test.JenkinsRule;
import org.xml.sax.SAXException;

public class CommonTestCases {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void rebuildDatabase(final JenkinsSearchBackend jenkinsSearchBackend, JenkinsRule rule)
            throws IOException, SAXException, InterruptedException, ExecutionException {
        URL statusUrl = new URL(rule.getURL(), "lucenesearchmanager/status");
        final URL rebuildUrl = new URL(rule.getURL(), "lucenesearchmanager/postRebuildDatabase");

        Future<Boolean> databaseRebuild = jenkinsSearchBackend.getBackgroundWorker().submit(new Callable<Boolean>() {

            @Override
            public Boolean call() throws Exception {
                LuceneManager.JSReturnCollection jsonObject = jenkinsSearchBackend.getRebuildStatus(rebuildUrl);
                assertEquals(GSON.toJson(jsonObject), 0, jsonObject.code);
                return true;
            }
        });
        LuceneManager.JSReturnCollection jsonObject = jenkinsSearchBackend.getRebuildStatus(statusUrl);
        while (jsonObject.running || jsonObject.neverStarted) {
            Thread.sleep(1000);
            jsonObject = jenkinsSearchBackend.getRebuildStatus(statusUrl);
        }
        assertEquals(GSON.toJson(jsonObject), 0, jsonObject.code);
        assertTrue("Something went wrong with rebuilding database", databaseRebuild.get());
    }

    public static void givenSearchWhenJobsWithBuildsAreExecutedThenTheyShouldBeSearchable(
            JenkinsSearchBackend jenkinsSearchBackend, JenkinsRule rule) throws IOException, ExecutionException,
            InterruptedException, SAXException, URISyntaxException {
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

    public static void givenSearchWhenIsNewItShouldSupportRebuildFromClean(JenkinsSearchBackend jenkinsSearchBackend,
            JenkinsRule rule) throws IOException, ExecutionException, InterruptedException, SAXException,
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
            e.printStackTrace();
            assertTrue(false);
        }
    }

}
