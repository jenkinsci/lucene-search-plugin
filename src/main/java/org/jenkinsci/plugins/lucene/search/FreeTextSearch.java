package org.jenkinsci.plugins.lucene.search;

import hudson.search.SearchResult;
import hudson.search.Search;

import java.io.IOException;

import javax.servlet.ServletException;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

public class FreeTextSearch extends Search {

    private final SearchBackendManager manager;

    public FreeTextSearch(final SearchBackendManager manager) {
        this.manager = manager;
        System.err.println("Created: " + getClass());
    }

    @Override
    public void doIndex(final StaplerRequest req, final StaplerResponse rsp) throws IOException, ServletException {
        // replace this with our own search page to show more result.
        // for example parts of the console output
        super.doIndex(req, rsp);
    }

    @Override
    public SearchResult getSuggestions(final StaplerRequest req, @QueryParameter final String query) {
        return manager.getBackend().getHits(query, true);
    }
}
