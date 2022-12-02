package org.jenkinsci.plugins.lucene.search;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.model.listeners.RunListener;

import java.io.IOException;

import javax.inject.Inject;

import org.apache.log4j.Logger;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;

@Extension
public class FreeTextRunListener extends RunListener<Run<?, ?>> {

    Logger logger = Logger.getLogger(FreeTextRunListener.class);

    @Inject
    SearchBackendManager searchBackendManager;

    @Override
    public void onCompleted(final Run<?, ?> build, @NonNull final TaskListener listener) {
        try {
            searchBackendManager.storeBuild(build);
        } catch (IOException e) {
            logger.error("When saving the finished build index: ", e);
        }
    }

    @Override
    public void onDeleted(final Run<?, ?> build) {
        try {
            searchBackendManager.removeBuild(build);
        } catch (IOException e) {
            logger.error("When removing the deleted build index: ", e);
        }
    }
}
