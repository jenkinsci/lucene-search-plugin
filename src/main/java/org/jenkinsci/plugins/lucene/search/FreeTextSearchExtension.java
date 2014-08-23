package org.jenkinsci.plugins.lucene.search;

import hudson.ExtensionPoint;
import hudson.model.AbstractBuild;
import jenkins.model.Jenkins;

import javax.swing.*;
import java.util.HashMap;
import java.util.Map;

public abstract class FreeTextSearchExtension implements ExtensionPoint {

    public static hudson.ExtensionList<FreeTextSearchExtension> all() {
        return Jenkins.getInstance().getExtensionList(FreeTextSearchExtension.class);
    }

    /**
     * Specifies the keyword that lucene stores the data as. This keyword is the same as the user can use to search for.
     * E.g. with keyword = "foo", the following query "foo:bar" will look for "bar" in the textresult for this extension.
     * @return the keyword, the word must be lower case
     */
    public abstract String getKeyword();

    /**
     * The text that will be searchable.
     */
    public abstract String getTextResult(AbstractBuild<?, ?> build);

    /**
     * If the original data should be stored in the index. This is necessary if the data should be displayed with
     * context around the match in the search result.
     */
    public boolean persist() {
        return false;
    }

    /**
     * If this keyword should be included in the default list of fields to search through.
     */
    public boolean isDefaultSearchable() {
        return true;
    }
}
