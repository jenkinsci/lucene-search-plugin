package org.jenkinsci.plugins.lucene.search;

import hudson.search.SearchItem;
import jenkins.model.Jenkins;

public abstract class FreeTextSearchItem implements SearchItem {

    /**
     * Turns the searchUrl into a url or path depending on if the
     * searchUrl starts with a '/'.
     *
     * @return something suitable for inserting into an href
     */
    public String getUrl() {
        String root = Jenkins.getInstance().getRootUrl();
        boolean rootHasSlash = !root.endsWith("/");
        boolean absolutePath = getSearchUrl().startsWith("/");
        if (absolutePath) {
            if (!rootHasSlash) {
                return root + getSearchUrl().substring(1);
            }
            return root + getSearchUrl();
        }
        return getSearchUrl();
    }

    public abstract String getIconFileName();

    public abstract boolean isShowConsole();
}
