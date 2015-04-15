package org.jenkinsci.plugins.lucene.search.databackend;

import static org.junit.Assert.assertEquals;
import hudson.model.FreeStyleProject;
import hudson.tasks.Shell;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.concurrent.ExecutionException;

import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.xml.sax.SAXException;

public class CommonTestCases {

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
        jenkinsSearchBackend.rebuildDatabase();
        assertEquals(3, jenkinsSearchBackend.search("echo").suggestions.size());
    }

    public static void givenSearchWhenIsNewItShouldSupportRebuildFromClean(JenkinsSearchBackend jenkinsSearchBackend,
            JenkinsRule rule) throws IOException, ExecutionException, InterruptedException, SAXException,
            URISyntaxException {
        assertEquals(0, jenkinsSearchBackend.search("echo").suggestions.size());
        jenkinsSearchBackend.rebuildDatabase();
        assertEquals(0, jenkinsSearchBackend.search("echo").suggestions.size());
        FreeStyleProject project1 = rule.createFreeStyleProject("project1");
        project1.getBuildersList().add(new Shell("echo $BUILD_TAG\n"));
        // Building
        project1.scheduleBuild2(0).get();
        project1.scheduleBuild2(0).get();
        project1.scheduleBuild2(0).get();
        jenkinsSearchBackend.rebuildDatabase();
        assertEquals(3, jenkinsSearchBackend.search("echo").suggestions.size());
    }

}
