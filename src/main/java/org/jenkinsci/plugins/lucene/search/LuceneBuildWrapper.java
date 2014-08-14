package org.jenkinsci.plugins.lucene.search;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;

import java.io.IOException;
import java.io.InputStream;

import net.sf.json.JSONObject;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.kohsuke.stapler.StaplerRequest;

@SuppressWarnings("rawtypes")
public class LuceneBuildWrapper extends BuildWrapper {

    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) throws IOException,
            InterruptedException {
        return new LuceneEnvironment();
    }

    public class LuceneEnvironment extends Environment {

        @Override
        public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
            LuceneManager.getInstance().storeBuild(build, listener);
            return super.tearDown(build, listener);
        }

    }

    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {
        @Override
        public String getDisplayName() {
            return "Lucene data sucker";
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

        @Override
        public LuceneBuildWrapper newInstance(StaplerRequest req, JSONObject formData) {
            return new LuceneBuildWrapper();
        }

    }

}
