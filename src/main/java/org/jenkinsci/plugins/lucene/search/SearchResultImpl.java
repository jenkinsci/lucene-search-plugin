package org.jenkinsci.plugins.lucene.search;

import hudson.search.SearchResult;
import hudson.search.SuggestedItem;
import java.util.ArrayList;

public class SearchResultImpl extends ArrayList<SuggestedItem> implements SearchResult {

  private static final long serialVersionUID = 1L;

  private static final boolean hasMoreResults = false;

  @Override
  public boolean hasMoreResults() {
    return hasMoreResults;
  }
}
