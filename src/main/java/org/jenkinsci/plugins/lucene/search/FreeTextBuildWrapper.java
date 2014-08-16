package org.jenkinsci.plugins.lucene.search;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;

import java.io.IOException;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.StaplerRequest;

import com.google.inject.Inject;

@SuppressWarnings("rawtypes")
public class FreeTextBuildWrapper extends BuildWrapper {

    @Override
    public Environment setUp(final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException,
            InterruptedException {
        return new LuceneEnvironment();
    }

    private SearchBackend getSearchBackend() {
        return ((DescriptorImpl) getDescriptor()).getSearchBackendManager().getBackend();
    }

    public class LuceneEnvironment extends Environment {

        @Override
        public boolean tearDown(final AbstractBuild build, final BuildListener listener) throws IOException, InterruptedException {
            boolean tearDown;
            try {
                tearDown = super.tearDown(build, listener);
            } finally {
                getSearchBackend().storeBuild(build, listener);
            }
            return tearDown;
        }
    }

    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {
        @Inject
        SearchBackendManager manager;

        @Override
        public String getDisplayName() {
            return "Lucene data sucker";
        }

        @Override
        public boolean isApplicable(final AbstractProject<?, ?> item) {
            return true;
        }

        private SearchBackendManager getSearchBackendManager() {
            return manager;
        }

        @Override
        public FreeTextBuildWrapper newInstance(final StaplerRequest req, final JSONObject formData) {
            return new FreeTextBuildWrapper();
        }

    }

}
