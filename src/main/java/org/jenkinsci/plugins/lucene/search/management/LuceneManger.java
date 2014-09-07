package org.jenkinsci.plugins.lucene.search.management;

import org.kohsuke.stapler.bind.JavaScriptMethod;

import hudson.Extension;
import hudson.model.ManagementLink;

@Extension
public class LuceneManger extends ManagementLink {

    @Override
    public String getDisplayName() {
        return "Lucene Search Manager";
    }

    @Override
    public String getIconFileName() {
        return "/plugin/lucene-search/icons/luceneserchmanager.jpg";
    }

    @Override
    public String getUrlName() {
        return "lucenesearchmanager";
    }

    @JavaScriptMethod
    public String doStuff() {
        return "Stuff done";
    }

    @JavaScriptMethod
    public String doStuffWithParameter(String someParam) {
        return "Stuff " + someParam;
    }
}
