package org.jenkinsci.plugins.lucene.search;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.model.listeners.RunListener;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.inject.Inject;

import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;

@Extension
public class FreeTextRunListener extends RunListener<Run<?, ?>> {

    @Inject
    SearchBackendManager searchBackendManager;

    @Override
    public void onCompleted(final Run<?, ?> build, @Nonnull final TaskListener listener) {
        try {
            searchBackendManager.storeBuild(build);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onDeleted(final Run<?, ?> build) {
        searchBackendManager.removeBuild(build);
    }
}
