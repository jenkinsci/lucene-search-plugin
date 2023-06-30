package org.jenkinsci.plugins.lucene.search;

import hudson.search.SearchItem;
import jenkins.model.Jenkins;

public abstract class FreeTextSearchItem implements SearchItem {

  public String getUrl() {
    String root = Jenkins.getInstance().getRootUrl();
    String searchUrl = getSearchUrl();
    boolean rootHasSlash = root.endsWith("/");
    boolean urlHasSlash = getSearchUrl().startsWith("/");
    if (rootHasSlash && urlHasSlash) {
      return root + searchUrl.substring(1);
    } else if (!rootHasSlash && !urlHasSlash) {
      return root + "/" + searchUrl;
    } else {
      return root + searchUrl;
    }
  }

  public abstract String getIconFileName();

  public abstract boolean isShowConsole();
}
