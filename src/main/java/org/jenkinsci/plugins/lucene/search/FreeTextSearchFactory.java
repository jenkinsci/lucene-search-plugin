package org.jenkinsci.plugins.lucene.search;

import com.google.inject.Inject;

import hudson.Extension;
import hudson.search.SearchFactory;
import hudson.search.SearchableModelObject;
import hudson.search.Search;

@Extension
public class FreeTextSearchFactory extends SearchFactory {
    @Inject
    SearchBackendManager manager;

    @Override
    public Search createFor(final SearchableModelObject owner) {
        return new FreeTextSearch(manager);
    }
}
