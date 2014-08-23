package org.jenkinsci.plugins.lucene.search;

import hudson.search.SearchIndex;
import hudson.search.SearchItem;

import java.util.List;
import java.util.regex.Pattern;

import jenkins.model.Jenkins;

public class FreeTextSearchItemImplementation implements SearchItem {

    private static final Pattern LINE_ENDINGS = Pattern.compile("(\\r\\n|\\n|\\r)");

    private final String buildNumber;
    private final String projectName;
    private final String[] bestFragments;

    public FreeTextSearchItemImplementation(final String projectName, final String buildNumber,
            final String[] bestFragments) {
        this.projectName = projectName;
        this.buildNumber = buildNumber;
        this.bestFragments = new String[bestFragments.length];
        for(int i = 0; i < bestFragments.length; i++) {
            this.bestFragments[i] = LINE_ENDINGS.matcher(bestFragments[i]).replaceAll("\1<br/>");
        }
    }

    public String getSearchUrl() {
        return Jenkins.getInstance().getRootUrl() + "/job/" + projectName + "/" + buildNumber + "/";
    }

    public String getSearchName() {
        return projectName + " #" + buildNumber;
    }

    public String[] getBestFragments() {
        return bestFragments;
    }

    public SearchIndex getSearchIndex() {
        return new SearchIndex() {

            public void suggest(final String token, final List<SearchItem> result) {
                // TODO Auto-generated method stub

            }

            public void find(final String token, final List<SearchItem> result) {
                // TODO Auto-generated method stub

            }
        };
    }
}