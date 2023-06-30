package org.jenkinsci.plugins.lucene.search;

import hudson.search.SearchIndex;
import hudson.search.SearchItem;

public class SearchItemWrapper extends FreeTextSearchItem {

  private final SearchItem item;

  public SearchItemWrapper(SearchItem item) {
    this.item = item;
  }

  @Override
  public String getSearchName() {
    return item.getSearchName();
  }

  @Override
  public String getSearchUrl() {
    return item.getSearchUrl();
  }

  @Override
  public SearchIndex getSearchIndex() {
    return item.getSearchIndex();
  }

  @Override
  public String getIconFileName() {
    return "search.png";
  }

  @Override
  public boolean isShowConsole() {
    return false;
  }
}
