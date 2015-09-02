package org.jenkinsci.plugins.lucene.search;

import hudson.search.SearchItem;
import jenkins.model.Jenkins;

public abstract class FreeTextSearchItem implements SearchItem {

    public String getAbsoluteURL() {
        String root = Jenkins.getInstance().getRootUrl();
        boolean needsExtraSlash = !root.endsWith("/");
        boolean hasExtraSlash = getSearchUrl().startsWith("/");
        if (needsExtraSlash && !hasExtraSlash) {
            return root + "/" + getSearchUrl();
        } else if (!needsExtraSlash && hasExtraSlash) {
            return root + getSearchUrl().substring(1);
        }
        return root + getSearchUrl();
    }

    public abstract String getIconFileName();

    public abstract boolean isShowConsole();
}
