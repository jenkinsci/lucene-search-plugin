package org.jenkinsci.plugins.lucene.search;

import hudson.ExtensionPoint;
import hudson.model.Run;
import jenkins.model.Jenkins;

/**
 * Extend this {@link ExtensionPoint} to add more data that can be searched.
 * @see <a href="https://wiki.jenkins-ci.org/display/JENKINS/Lucene-Search#Lucene-Search-Example">Lucene-Search-Example</a> for an example.
 */
public abstract class FreeTextSearchExtension implements ExtensionPoint {

    public static hudson.ExtensionList<FreeTextSearchExtension> all() {
        return Jenkins.getInstance().getExtensionList(FreeTextSearchExtension.class);
    }

    /**
     * Specifies the keyword that lucene stores the data as. This keyword is the same as the user can use to search for.
     * E.g. with keyword = "foo", the following query "foo:bar" will look for "bar" in the textresult for this extension.
     *
     * Care must be taken to make sure this does not collide with any fieldName in {@link Field}.
     *
     * @return the keyword, the word must be lower case
     */
    public abstract String getKeyword();

    /**
     * The text that will be searchable.
     */
    public abstract String getTextResult(Run<?, ?> run);

    /**
     * If the original data should be stored in the index. This is necessary if the data should be displayed with
     * context around the match in the search result.
     */
    public boolean isPersist() {
        return false;
    }

    /**
     * If this keyword should be included in the default list of fields to search through.
     */
    public boolean isDefaultSearchable() {
        return true;
    }
}
