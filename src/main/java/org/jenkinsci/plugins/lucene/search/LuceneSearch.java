package org.jenkinsci.plugins.lucene.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import hudson.search.SearchIndex;
import hudson.search.SearchItem;
import hudson.search.SearchResult;
import hudson.search.Search;
import hudson.search.SuggestedItem;

public class LuceneSearch extends Search {

    @Override
    public void doIndex(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        // TODO Auto-generated method stub
        super.doIndex(req, rsp);
    }

    @Override
    public void doSuggestOpenSearch(StaplerRequest req, StaplerResponse rsp, @QueryParameter String q) throws IOException,
            ServletException {
        // TODO Auto-generated method stub
        super.doSuggestOpenSearch(req, rsp, q);
    }

    @Override
    public void doSuggest(StaplerRequest req, StaplerResponse rsp, @QueryParameter String query) throws IOException, ServletException {
        // TODO Auto-generated method stub
        super.doSuggest(req, rsp, query);
    }

    @Override
    public SearchResult getSuggestions(StaplerRequest req, @QueryParameter String query) {
        // TODO Auto-generated method stub
        //return super.getSuggestions(req, query);
        LuceneSearchResultImpl nisse = new LuceneSearchResultImpl();
        nisse.add(new SuggestedItem(new SearchItem() {
            
            public String getSearchUrl() {
                return "URL";
            }
            
            public String getSearchName() {
                return "NAME";
            }
            
            public SearchIndex getSearchIndex() {
                return new SearchIndex() {
                    
                    public void suggest(String token, List<SearchItem> result) {
                        // TODO Auto-generated method stub
                        
                    }
                    
                    public void find(String token, List<SearchItem> result) {
                        // TODO Auto-generated method stub
                        
                    }
                };
            }
        }));
        return nisse;
    }

    private static class LuceneSearchResultImpl extends ArrayList<SuggestedItem> implements SearchResult {

        private boolean hasMoreResults = false;

        public boolean hasMoreResults() {
            return hasMoreResults;
        }
    }

    
}
