package org.jenkinsci.plugins.lucene.search;

import com.google.inject.Inject;
import hudson.Extension;
import hudson.model.AllView;
import hudson.model.ViewDescriptor;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;
import org.jenkinsci.plugins.lucene.search.databackend.SearchFieldDefinition;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.jws.WebMethod;
import java.io.IOException;
import java.util.List;

public class FreeTextSearchHelp extends AllView {

    @Inject
    private SearchBackendManager searchBackendManager;

    @DataBoundConstructor
    public FreeTextSearchHelp() {
        super("/luceneSearchHelp");
    }

    public List<SearchFieldDefinition> getSearchHelp() throws IOException {
        return searchBackendManager.getSearchFieldDefinitions();
    }

    @Extension
    public static final class DescriptorImpl extends ViewDescriptor {

        public DescriptorImpl() {
            System.err.println("foooo");
        }

        public String getDisplayName() {
            return Messages.displayName();
        }
    }
}
