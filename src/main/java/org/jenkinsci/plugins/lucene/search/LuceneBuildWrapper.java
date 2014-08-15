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

@SuppressWarnings("rawtypes")
public class LuceneBuildWrapper extends BuildWrapper {

    @Override
    public Environment setUp(final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException,
            InterruptedException {
        return new LuceneEnvironment();
    }

    public class LuceneEnvironment extends Environment {

        @Override
        public boolean tearDown(final AbstractBuild build, final BuildListener listener) throws IOException, InterruptedException {
            boolean tearDown;
            try {
                tearDown = super.tearDown(build, listener);
            } finally {
                LuceneManager.getInstance().storeBuild(build, listener);
            }
            return tearDown;
        }

    }

    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {
        @Override
        public String getDisplayName() {
            return "Lucene data sucker";
        }

        @Override
        public boolean isApplicable(final AbstractProject<?, ?> item) {
            return true;
        }

        @Override
        public LuceneBuildWrapper newInstance(final StaplerRequest req, final JSONObject formData) {
            return new LuceneBuildWrapper();
        }

    }

}
