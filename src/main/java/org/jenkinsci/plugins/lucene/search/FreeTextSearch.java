package org.jenkinsci.plugins.lucene.search;

import hudson.search.*;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;
import org.jenkinsci.plugins.lucene.search.databackend.SearchFieldDefinition;
import org.kohsuke.stapler.Ancestor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.bind.JavaScriptMethod;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FreeTextSearch extends Search {
    private final static Logger LOGGER = Logger.getLogger(Search.class.getName());

    private final SearchBackendManager manager;

    private final Map<Integer, List<FreeTextSearchItem>> pageMap;

    private int curr_page;

    private int max_page;

    private String query;

    public FreeTextSearch(final SearchBackendManager manager) {
        this.manager = manager;
        curr_page = 0;
        max_page = Integer.MAX_VALUE;
        pageMap = new HashMap<>();
    }

    public int getPageNum() {
        return curr_page;
    }

    private List<FreeTextSearchItem> normalSearch(StaplerRequest req, String query) {
        List<FreeTextSearchItem> searchResults = new ArrayList<FreeTextSearchItem>();

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
                    searchResults.add(new SearchItemWrapper(target.item));
                }
            }
        }
        return searchResults;
    }

    public List<FreeTextSearchItem> getPage() {
        List<FreeTextSearchItem> page = pageMap.get(curr_page);
        if (page == null) {
            page = new ArrayList<>(manager.getHits(query, true));
            if (page.isEmpty()) {
                max_page = curr_page;
            }
            pageMap.put(curr_page, page);
        }
        return page;
    }

    public boolean isEmptyResult() {
        return getPage().isEmpty();
    }

    @JavaScriptMethod
    public boolean isFirstPage() {
        return curr_page <= 1;
    }

    @JavaScriptMethod
    public boolean isLastPage() {
        return curr_page >= max_page;
    }

    @Override
    public void doIndex(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        query = req.getParameter("q");
        if (query != null) {
            List<FreeTextSearchItem> hits = normalSearch(req, query);
            hits.addAll(manager.getHits(query, false));
            pageMap.put(1, hits);
        }
        req.getView(this, "search-results.jelly").forward(req, rsp);
    }

    @Override
    public SearchResult getSuggestions(final StaplerRequest req, @QueryParameter final String query) {
        SearchResult suggestedItems = super.getSuggestions(req, query);
        suggestedItems.addAll(manager.getSuggestedItems(query));
        return suggestedItems;
    }

    @JavaScriptMethod
    public List<FreeTextSearchItem> prev() {
        curr_page = Math.max(curr_page - 1, 1);
        return getPage();
    }

    @JavaScriptMethod
    public List<FreeTextSearchItem> next() {
        curr_page = Math.min(curr_page + 1, max_page);
        return getPage();
    }
}
