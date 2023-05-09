package org.jenkinsci.plugins.lucene.search.config;

import com.google.common.annotations.VisibleForTesting;
import hudson.Extension;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

@Extension
public class SearchBackendConfiguration extends GlobalConfiguration {

    private static final String LUCENE_PATH = "lucenePath";
    private static final String USE_SECURITY = "useSecurity";

    @Inject
    private transient SearchBackendManager backendManager;

    private File lucenePath = new File(Jenkins.getInstance().getRootDir(), "luceneIndex");
    private boolean useSecurity = true;
    private boolean luceneSearchEnabled = true;

    @DataBoundConstructor
    public SearchBackendConfiguration(final String lucenePath,
           boolean useSecurity, boolean luceneSearchEnabled) {
        this(new File(lucenePath), useSecurity, luceneSearchEnabled);
    }

    public SearchBackendConfiguration(final File lucenePath, boolean useSecurity, boolean luceneSearchEnabled) {
        load();
        this.lucenePath = lucenePath;
        this.useSecurity = useSecurity;
        this.luceneSearchEnabled = luceneSearchEnabled;
    }

    public SearchBackendConfiguration() {
        load();
    }

    public void setLucenePath(final File lucenePath) {
        Jenkins.get().getACL().checkPermission(Jenkins.ADMINISTER);
        this.lucenePath = lucenePath;
    }

    public FormValidation doCheckLucenePath(@QueryParameter final String lucenePath) {
        Jenkins.get().getACL().checkPermission(Jenkins.ADMINISTER);
        try {
            new File(lucenePath);
            return FormValidation.ok();
        } catch (RuntimeException e) {
            return FormValidation.error(e.getMessage());
        }
    }

    @Override
    public boolean configure(final StaplerRequest req, final JSONObject json) throws FormException {
        JSONObject selectedJson = json.getJSONObject("searchBackend");
        if (selectedJson.containsKey(LUCENE_PATH)) {
            String lucenePath = selectedJson.getString(LUCENE_PATH);
            ensureNotError(doCheckLucenePath(lucenePath), LUCENE_PATH);
            setLucenePath(new File(lucenePath));
        }
        if (json.containsKey(USE_SECURITY)) {
            setUseSecurity(json.getBoolean(USE_SECURITY));
        }
        try {
            reconfigure();
        } catch (IOException e) {
            //
        }
        return super.configure(req, json);
    }

    @VisibleForTesting
    public void reconfigure() throws IOException {
        Jenkins.get().getACL().checkPermission(Jenkins.ADMINISTER);
        backendManager.reconfigure(getConfig());
        save();
    }

    private void ensureNotError(FormValidation formValidation, String field) throws FormException {
        if (formValidation.kind == FormValidation.Kind.ERROR) {
            throw new FormException("Incorrect search config field: " + field, field);
        }
    }

    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("lucenePath", lucenePath);
        return config;
    }

    public boolean isUseSecurity() {
        return useSecurity;
    }

    public void setUseSecurity(boolean useSecurity) {
        Jenkins.get().getACL().checkPermission(Jenkins.ADMINISTER);
        this.useSecurity = useSecurity;
    }

    public boolean isLuceneSearchEnabled() {
        return luceneSearchEnabled;
    }

    public void setLuceneSearchEnabled(boolean luceneSearchEnabled) {
        this.luceneSearchEnabled = luceneSearchEnabled;
    }
}
