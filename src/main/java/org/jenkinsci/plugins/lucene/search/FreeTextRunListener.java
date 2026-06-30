package org.jenkinsci.plugins.lucene.search;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
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
    public void onStarted(final Run<?, ?> build, @NonNull final TaskListener listener) {
        logger.debug("onStarted: storing build=" + build.getFullDisplayName()
                + " number=" + build.getNumber()
                + " isBuilding=" + build.isBuilding()
                + " isLogUpdated=" + build.isLogUpdated());
        try {
            searchBackendManager.storeBuild(build);
            logger.debug("onStarted: stored OK build=" + build.getFullDisplayName());
        } catch (IOException e) {
            logger.error("When saving the started build index: ", e);
        }
    }

    @Override
    public void onCompleted(final Run<?, ?> build, @NonNull final TaskListener listener) {
        logger.debug("onCompleted: storing build=" + build.getFullDisplayName()
                + " number=" + build.getNumber()
                + " result=" + build.getResult()
                + " duration=" + build.getDuration());
        try {
            searchBackendManager.storeBuild(build);
            logger.debug("onCompleted: stored OK build=" + build.getFullDisplayName());
        } catch (IOException e) {
            logger.error("When saving the finished build index: ", e);
        }
    }

    @Override
    public void onDeleted(final Run<?, ?> build) {
        logger.debug("onDeleted: removing build=" + build.getFullDisplayName()
                + " number=" + build.getNumber());
        try {
            searchBackendManager.removeBuild(build);
            logger.debug("onDeleted: removed OK build=" + build.getFullDisplayName());
        } catch (IOException e) {
            logger.error("When removing the deleted build index: ", e);
        }
    }
}
