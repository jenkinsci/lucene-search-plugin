package org.jenkinsci.plugins.lucene.search.management;

import hudson.Extension;
import hudson.model.ManagementLink;
import net.sf.json.JSONSerializer;
import org.jenkinsci.plugins.lucene.search.databackend.ManagerProgress;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.bind.JavaScriptMethod;

import javax.inject.Inject;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.Writer;

@Extension
public class LuceneManager extends ManagementLink {

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
    public JSReturnCollection rebuildDatabase(int workers) {
        this.workers = workers;
        JSReturnCollection statement = verifyNotInProgress();
        if (statement.code == 0) {
            progress = new ManagerProgress();
            backendManager.rebuildDatabase(progress, workers);

            statement.message = "Work completed succesfully";
            statement.code = 0;
        }
        return statement;
    }

    public void doRebuildDatabase(StaplerRequest req, StaplerResponse rsp, @QueryParameter int workers) throws IOException, ServletException {
        writeStatus(rsp, rebuildDatabase(workers));
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
    public JSReturnCollection abort() throws Exception {
        JSReturnCollection statement = new JSReturnCollection();
        statement.message = "Not implemented";
        statement.code = 1;
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
