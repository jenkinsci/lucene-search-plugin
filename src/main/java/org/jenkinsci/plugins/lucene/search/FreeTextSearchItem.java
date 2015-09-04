package org.jenkinsci.plugins.lucene.search;

import hudson.search.SearchItem;
import jenkins.model.Jenkins;

public abstract class FreeTextSearchItem implements SearchItem {

    /**
     * Turns the searchUrl into a url or path depending on if the searchUrl
     * starts with a '/'.
     * But the relative url will be from <ROOT> of jenkins but when we just use the relative url 
     * it will append it to the current URL <ROOT>/search.
     * 
     * @return something suitable for inserting into an href
     */
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
