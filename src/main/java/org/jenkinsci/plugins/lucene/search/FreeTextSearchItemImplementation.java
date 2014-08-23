package org.jenkinsci.plugins.lucene.search;

import hudson.search.SearchIndex;
import hudson.search.SearchItem;

import java.util.List;

import jenkins.model.Jenkins;

public class FreeTextSearchItemImplementation implements SearchItem {

    private final String buildNumber;
    private final String projectName;
    private final String iconFileName;
    private final String[] bestFragments;

    public FreeTextSearchItemImplementation(final String projectName, final String buildNumber,
            final String[] bestFragments, final String iconFileName) {
        this.projectName = projectName;
        this.buildNumber = buildNumber;
        this.bestFragments = bestFragments;
        this.iconFileName = iconFileName;
    }

    @Override
    public String getSearchUrl() {
        return Jenkins.getInstance().getRootUrl() + "/job/" + projectName + "/" + buildNumber + "/";
    }

    @Override
    public String getSearchName() {
        return projectName + " #" + buildNumber;
    }

    public String[] getBestFragments() {
        return bestFragments;
    }

    public String getIconFileName() {
        return iconFileName;
    }

    @Override
    public SearchIndex getSearchIndex() {
        return new SearchIndex() {

            @Override
            public void suggest(final String token, final List<SearchItem> result) {
                // TODO Auto-generated method stub

            }

            @Override
            public void find(final String token, final List<SearchItem> result) {
                // TODO Auto-generated method stub

            }
        };
    }
}