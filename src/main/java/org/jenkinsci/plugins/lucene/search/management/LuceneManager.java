package org.jenkinsci.plugins.lucene.search.management;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.ManagementLink;
import jenkins.model.Jenkins;
import net.sf.json.JSONSerializer;
import org.apache.log4j.Logger;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendConfiguration;
import org.jenkinsci.plugins.lucene.search.databackend.ManagerProgress;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackend;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.bind.JavaScriptMethod;

import javax.inject.Inject;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

@Extension
public class LuceneManager extends ManagementLink {

    private static final Logger LOGGER = Logger.getLogger(SearchBackend.class);

    @Inject
    private transient SearchBackendManager backendManager;
    private ManagerProgress progress;
    private int workers = 0;

    @Override
    public String getDisplayName() {
        return "Lucene Search Manager";
    }

    @Override
    public String getIconFileName() {
        return "/plugin/lucene-search/lucenesearchmanager.jpg";
    }

    @Override
    public String getUrlName() {
        return "lucenesearchmanager";
    }

    @JavaScriptMethod
    public JSReturnCollection rebuildDatabase(int workers, String jobNames, String overwrite) {
        JSReturnCollection statement = verifyNotInProgress();
        this.workers = workers;
        if (this.workers <= 0) {
            statement.message = "Invalid number of workers";
            statement.code = 1;
            return statement;
        }

        if (statement.code == 0) {
            progress = new ManagerProgress();
            Set<String> jobs = new HashSet(Arrays.asList(jobNames.split("\\s+")));
            jobs.removeAll(Collections.singleton(""));
            if (checkJobNames(jobs)) {
                backendManager.rebuildDatabase(progress, this.workers, jobs, overwrite.equals("overwrite"));
                statement.message = "Work completed successfully";
                statement.code = 0;
            } else {
                progress.completedWithErrors(new Exception("The entered job names are invalid"));
                progress.setFinished();
            }
        }
        return statement;
    }

    private boolean checkJobNames(Set<String> jobs) {
        List<Job> allItems = Jenkins.getInstance().getAllItems(Job.class);
        int size = jobs.size();
        for (Job job : allItems) {
            if (jobs.contains(job.getName())) {
                size--;
            }
        }
        return size == 0;
    }

    public void doPostRebuildDatabase(StaplerRequest req, StaplerResponse rsp, @QueryParameter int workers)
            throws IOException, ServletException {
        writeStatus(rsp, rebuildDatabase(workers, "", "overwrite"));
    }

    private JSReturnCollection verifyNotInProgress() {
        JSReturnCollection statement = new JSReturnCollection();
        if (this.progress != null && !this.progress.isFinished()) {
            statement.message = "Currently working, wait for it ....";
            statement.code = 1;
            statement.running = true;
            return statement;
        }
        return statement;
    }

    @JavaScriptMethod
    public JSReturnCollection abort() {
        JSReturnCollection statement = verifyNotInProgress();
//        if (statement.code == 1) {
//            statement.message = "Aborted";
//            statement.code = 0;
//            statement.running = false;
//            statement.neverStarted = true;
//            try {
//                progress.assertNoErrors();
//            } catch (Exception e) {
//                progress.completedWithErrors(e);
//                return statement;
//            }
//            progress.jobComplete();
//        }
        this.progress = null;
        return statement;
    }

    @JavaScriptMethod
    public JSReturnCollection clean() {
        JSReturnCollection statement = verifyNotInProgress();
        if (statement.code == 0) {
            progress = new ManagerProgress();
            backendManager.clean(progress);
            statement.message = "Index cleaned successfully";
            statement.code = 0;
        }
        return statement;
    }

    @JavaScriptMethod
    public JSReturnCollection getStatus() {
        JSReturnCollection statement = new JSReturnCollection();
        if (progress != null) {
            statement.progress = progress;
            statement.workers = workers;
            switch (progress.getState()) {
                case COMPLETE:
                    statement.message = "Completed without errors";
                    break;
                case COMPLETE_WITH_ERROR:
                    statement.message = progress.getReasonsAsString();
                    statement.code = 2;
                    break;
                case PROCESSING:
                    statement.running = true;
                    statement.message = "processing";
                    break;
            }
        } else {
            statement.message = "Never started";
            statement.neverStarted = true;
        }
        return statement;
    }

    // Primarily for testing
    public void doStatus(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        JSReturnCollection status = getStatus();
        writeStatus(rsp, status);
    }

    public void writeStatus(StaplerResponse rsp, JSReturnCollection status) throws IOException {
        Writer compressedWriter = rsp.getWriter();
        JSONSerializer.toJSON(status).write(compressedWriter);
        rsp.setStatus(200);
        compressedWriter.flush();
    }

    public static class JSReturnCollection {
        public int code;
        public String message = "";
        public boolean running;
        public ManagerProgress progress;
        public int workers;
        public boolean neverStarted;
    }
}
