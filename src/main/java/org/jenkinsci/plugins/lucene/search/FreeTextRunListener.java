package org.jenkinsci.plugins.lucene.search;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.listeners.RunListener;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.inject.Inject;

@Extension
public class FreeTextRunListener extends RunListener<AbstractBuild<?, ?>> {

    @Inject
    SearchBackendManager searchBackendManager;

    @Override
    public void onCompleted(final AbstractBuild<?, ?> build, @Nonnull final TaskListener listener) {
        try {
            searchBackendManager.getBackend().storeBuild(build);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onDeleted(final AbstractBuild<?, ?> build) {
        searchBackendManager.getBackend().removeBuild(build);
    }
}
