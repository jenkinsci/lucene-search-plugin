package org.jenkinsci.plugins.lucene.search.management;

import hudson.Extension;
import hudson.model.ManagementLink;

import javax.inject.Inject;

import org.jenkinsci.plugins.lucene.search.SearchBackendManager;
import org.jenkinsci.plugins.lucene.search.databackend.ManagerProgress;
import org.kohsuke.stapler.bind.JavaScriptMethod;

@Extension
public class LuceneManager extends ManagementLink {

    @Inject
    private transient SearchBackendManager backendManager;
    private ManagerProgress progress;

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
    public JSReturnCollection rebuildDatabase() {
        JSReturnCollection statement = verifyNotInProgress();
        if (statement.code == 0) {
            progress = new ManagerProgress();
            backendManager.rebuildDatabase(progress);

            statement.message = "Work started succesfully";
            statement.code = 0;
        }
        return statement;
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
            switch (progress.getState()) {
            case COMPLETE:
                statement.message = "Completed without errors";
                break;
            case COMPLETE_WITH_ERROR:
                statement.message = progress.getReasonsAsString();
                statement.code = 2;
                break;
            case PROCESSING:
                statement.message = "processing";
                break;
            }
        } else {
            statement.message = "Never started";
        }

        return statement;
    }

    public static class JSReturnCollection {
        public int code = 0;
        public String message = "";
        public boolean running = false;
        public ManagerProgress progress = null; 
    }

}
