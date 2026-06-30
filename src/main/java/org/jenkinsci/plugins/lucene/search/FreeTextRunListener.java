package org.jenkinsci.plugins.lucene.search;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.io.IOException;
import javax.inject.Inject;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;

@Extension
public class FreeTextRunListener extends RunListener<Run<?, ?>> {

    private static final Logger LOG = Logger.getLogger(FreeTextRunListener.class.getName());

    @Inject
    SearchBackendManager searchBackendManager;

    @Override
    public void onStarted(final Run<?, ?> build, @NonNull final TaskListener listener) {
        LOG.fine("onStarted: storing build=" + build.getFullDisplayName()
                + " number=" + build.getNumber()
                + " isBuilding=" + build.isBuilding()
                + " isLogUpdated=" + build.isLogUpdated());
        try {
            searchBackendManager.storeBuild(build);
            LOG.fine("onStarted: stored OK build=" + build.getFullDisplayName());
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "When saving the started build index: ", e);
        }
    }

    @Override
    public void onCompleted(final Run<?, ?> build, @NonNull final TaskListener listener) {
        LOG.fine("onCompleted: storing build=" + build.getFullDisplayName()
                + " number=" + build.getNumber()
                + " result=" + build.getResult()
                + " duration=" + build.getDuration());
        try {
            searchBackendManager.storeBuild(build);
            LOG.fine("onCompleted: stored OK build=" + build.getFullDisplayName());
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "When saving the finished build index: ", e);
        }
    }

    @Override
    public void onDeleted(final Run<?, ?> build) {
        LOG.fine("onDeleted: removing build=" + build.getFullDisplayName()
                + " number=" + build.getNumber());
        try {
            searchBackendManager.removeBuild(build);
            LOG.fine("onDeleted: removed OK build=" + build.getFullDisplayName());
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "When removing the deleted build index: ", e);
        }
    }
}
