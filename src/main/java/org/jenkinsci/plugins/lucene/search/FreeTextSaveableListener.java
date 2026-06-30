package org.jenkinsci.plugins.lucene.search;

import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Run;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import org.apache.log4j.Logger;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;

@Extension
public class FreeTextSaveableListener extends SaveableListener {

    private static final ExecutorService INDEX_UPDATE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lucene-search-index-update");
        thread.setDaemon(true);
        return thread;
    });

    Logger logger = Logger.getLogger(FreeTextSaveableListener.class);

    @Inject
    SearchBackendManager searchBackendManager;

    @Override
    public void onChange(Saveable o, XmlFile file) {
        if (o instanceof Run) {
            Run<?, ?> run = (Run<?, ?>) o;
            // onStarted already indexed this build, and onCompleted will do the
            // final index. When we're NOT indexing console logs, nothing in the
            // searchable document changes between start and finish — project
            // name, build number, parameters, and display name are all fixed at
            // build start. Skipping mid-build saves eliminates the constant index
            // churn we'd otherwise cause every 1-2 seconds.
            if (run.isBuilding() && !searchBackendManager.isCollectingBuildLogs()) {
                logger.debug("onChange: skipping still-building " + run.getFullDisplayName()
                        + " (no indexed fields change)");
                return;
            }
            logger.debug("onChange: build=" + run.getFullDisplayName()
                    + " number=" + run.getNumber()
                    + " isBuilding=" + run.isBuilding()
                    + " isLogUpdated=" + run.isLogUpdated()
                    + " file=" + (file != null ? file.getFile() : "null"));
            updateIndex(run);
        }
    }

    private void updateIndex(Run run) {
        SearchBackendManager manager = searchBackendManager;
        CompletableFuture.runAsync(
                () -> {
                    try {
                        // storeBuild uses updateDocument (atomic upsert) — no need
                        // to remove first; the build never disappears from searches.
                        logger.debug("updateIndex: upsert build=" + run.getFullDisplayName());
                        manager.storeBuild(run);
                        logger.debug("updateIndex: done build=" + run.getFullDisplayName());
                    } catch (IOException e) {
                        logger.error("update index failed: ", e);
                    }
                },
                INDEX_UPDATE_EXECUTOR)
                .join();
    }
}
