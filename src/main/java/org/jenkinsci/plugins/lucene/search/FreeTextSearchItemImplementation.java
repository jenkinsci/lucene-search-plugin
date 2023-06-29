package org.jenkinsci.plugins.lucene.search;

import hudson.model.BallColor;
import hudson.search.SearchIndex;
import hudson.search.SearchItem;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class FreeTextSearchItemImplementation extends FreeTextSearchItem {

  private static final Pattern LINE_ENDINGS = Pattern.compile("(\\r\\n|\\n|\\r)");

  private final String projectName;
  private final boolean isShowConsole;
  private final List<String> bestFragments;
  private final String url;
  private final String searchName;

  public FreeTextSearchItemImplementation(
      final String searchName,
      final String projectName,
      final String[] bestFragments,
      final String url,
      boolean isShowConsole) {
    this.searchName = searchName;
    this.projectName = projectName;
    this.url = url;
    this.isShowConsole = isShowConsole;
    this.bestFragments = new ArrayList<>(bestFragments.length);
    for (int i = 0; i < bestFragments.length; i++) {
      this.bestFragments.add(LINE_ENDINGS.matcher(bestFragments[i]).replaceAll("<br/>"));
    }
  }

  @Override
  public String getSearchUrl() {
    return url;
  }

  @Override
  public String getSearchName() {
    return searchName;
  }

  public String getProjectName() {
    return projectName;
  }

  public String[] getBestFragments() {
    return bestFragments.toArray(new String[bestFragments.size()]);
  }

  @Override
  public String getIconFileName() {
    // return blue by default; this part could be extended
    return BallColor.BLUE.getImage();
  }

  @Override
  public boolean isShowConsole() {
    return isShowConsole;
  }

  @Override
  public SearchIndex getSearchIndex() {
    return new SearchIndex() {

      @Override
      public void suggest(final String token, final List<SearchItem> result) {}

      @Override
      public void find(final String token, final List<SearchItem> result) {}
    };
  }
}
