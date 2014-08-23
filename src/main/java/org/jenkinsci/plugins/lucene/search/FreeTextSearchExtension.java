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

    public abstract String getKeyWord();

    public abstract String getTextResult(AbstractBuild<?, ?> build);

    public boolean persist() {
        return false;
    }
}
