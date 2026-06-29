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
            Run run = (Run) o;
            updateIndex(run);
        }
    }

    private void updateIndex(Run run) {
        SearchBackendManager manager = searchBackendManager;
        CompletableFuture.runAsync(
                () -> {
                    try {
                        manager.removeBuild(run);
                        manager.storeBuild(run);
                    } catch (IOException e) {
                        logger.error("update index failed: ", e);
                    }
                },
                INDEX_UPDATE_EXECUTOR)
                .join();
    }
}
