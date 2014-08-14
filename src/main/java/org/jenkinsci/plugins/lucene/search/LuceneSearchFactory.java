package org.jenkinsci.plugins.lucene.search;

import jenkins.model.Jenkins;
import hudson.Extension;
import hudson.search.SearchFactory;
import hudson.search.SearchableModelObject;
import hudson.search.Search;

@Extension
public class LuceneSearchFactory extends SearchFactory{

    @Override
    public Search createFor(SearchableModelObject owner) {
        // TODO Auto-generated method stub
        //Jenkins.getInstance()
        System.err.println("hello kitty");
        
        return new LuceneSearch();
    }

}
