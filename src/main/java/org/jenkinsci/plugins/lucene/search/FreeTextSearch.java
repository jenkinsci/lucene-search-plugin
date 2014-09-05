package org.jenkinsci.plugins.lucene.search;

import hudson.search.Search;
import hudson.search.SearchResult;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

public class FreeTextSearch extends Search {
    private final static Logger LOGGER = Logger.getLogger(Search.class.getName());

    private final SearchBackendManager manager;

    private List<FreeTextSearchItemImplementation> hits = Collections.emptyList();

    public FreeTextSearch(final SearchBackendManager manager) {
        this.manager = manager;
    }

    public List<FreeTextSearchItemImplementation> getHits() {
        return hits;
    }

    @Override
    public void doIndex(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        String query = req.getParameter("q");
        if (query != null) {
            hits = manager.getHits(query, true);
        }
        if (hits.isEmpty()) {
            rsp.setStatus(SC_NOT_FOUND);
        }
        req.getView(this, "search-results.jelly").forward(req, rsp);
    }

    @Override
    public SearchResult getSuggestions(final StaplerRequest req, @QueryParameter final String query) {
        return manager.getSuggestedItems(query);
    }
}
