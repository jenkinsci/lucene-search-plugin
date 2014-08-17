package org.jenkinsci.plugins.lucene.search;

import hudson.model.AbstractBuild;
import hudson.search.SearchResult;

import java.io.IOException;
import java.util.Map;

import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;

public interface SearchBackend {
    public void storeBuild(final AbstractBuild<?, ?> build) throws IOException;

    public SearchResult getHits(final String query, final boolean includeHighlights);

    public SearchBackendEngine getEngine();

    public SearchBackend reconfigure(Map<String, Object> config);

    public void removeBuild(AbstractBuild<?, ?> build);
}
