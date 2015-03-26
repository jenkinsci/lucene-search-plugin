package org.jenkinsci.plugins.lucene.search;

import hudson.search.Search;
import hudson.search.SearchIndex;
import hudson.search.SearchItem;
import hudson.search.SearchResult;
import hudson.search.SearchableModelObject;
import hudson.search.SuggestedItem;

import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;
import org.jenkinsci.plugins.lucene.search.databackend.SearchFieldDefinition;
import org.kohsuke.stapler.Ancestor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.ServletException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FreeTextSearch extends Search {
    private final static Logger LOGGER = Logger.getLogger(Search.class.getName());

    private final SearchBackendManager manager;

    private List<FreeTextSearchItemImplementation> hits = Collections.emptyList();
    private List<SearchItem> searchModelHits = Collections.emptyList();

    public FreeTextSearch(final SearchBackendManager manager) {
        this.manager = manager;
    }

    public List<FreeTextSearchItemImplementation> getHits() {
        return hits;
    }

    public List<SearchItem> getSearchModelHits() {
        return searchModelHits;
    }

    private List<SearchItem> normalSearch(StaplerRequest req, String query) {
        List<SearchItem> searchResults = new ArrayList<SearchItem>() {
            @Override
            public Iterator<SearchItem> iterator() {
                return super.iterator();
            }
        };

        List<Ancestor> l = req.getAncestors();
        for (int i = l.size() - 1; i >= 0; i--) {
            Ancestor a = l.get(i);
            if (a.getObject() instanceof SearchableModelObject) {
                SearchableModelObject smo = (SearchableModelObject) a.getObject();
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(String.format("smo.displayName=%s, searchName=%s", smo.getDisplayName(),
                            smo.getSearchName()));
                }

                SearchIndex index = smo.getSearchIndex();
                SuggestedItem target = find(index, query, smo);
                if (target != null) {
                    searchResults.add(target.item);
                }
            }
        }
        return searchResults;
    }

    public boolean isEmptyResult() {
        return hits.isEmpty() && searchModelHits.isEmpty();
    }

    @Override
    public void doIndex(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        String query = req.getParameter("q");
        if (query != null) {
            searchModelHits = normalSearch(req, query);
            hits = manager.getHits(query, true);
        }
        req.getView(this, "search-results.jelly").forward(req, rsp);
    }

    @Override
    public SearchResult getSuggestions(final StaplerRequest req, @QueryParameter final String query) {
        SearchResult suggestedItems = super.getSuggestions(req, query);
        suggestedItems.addAll(manager.getSuggestedItems(query));
        return suggestedItems;
    }

    public List<SearchFieldDefinition> getSearchHelp() throws IOException {
        return manager.getSearchFieldDefinitions();
    }
}
