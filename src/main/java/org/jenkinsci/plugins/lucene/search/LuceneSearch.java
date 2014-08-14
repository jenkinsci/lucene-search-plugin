package org.jenkinsci.plugins.lucene.search;

import hudson.search.SearchResult;
import hudson.search.Search;

import java.io.IOException;

import javax.servlet.ServletException;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

public class LuceneSearch extends Search {

    @Override
    public void doIndex(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        // replace this with our own search page to show more result.
        // for example parts of the console output
        super.doIndex(req, rsp);
    }

    @Override
    public SearchResult getSuggestions(StaplerRequest req, @QueryParameter String query) {
        try {
            return LuceneManager.getInstance().getHits(query);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
