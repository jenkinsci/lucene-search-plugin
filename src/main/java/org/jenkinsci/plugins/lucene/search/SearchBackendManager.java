package org.jenkinsci.plugins.lucene.search;

import hudson.Extension;

import java.util.Map;

import javax.inject.Inject;

import org.jenkinsci.plugins.lucene.search.config.SearchBackendConfiguration;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;

@Extension
public class SearchBackendManager {
    private SearchBackend instance;

    @Inject
    private SearchBackendConfiguration backendConfig;

    public synchronized SearchBackend getBackend() {
        if (instance == null) {
            SearchBackendEngine engine = backendConfig.getSearchBackendEngine();
            switch (engine) {
                case LUCENE:
                    instance = LuceneSearchBackend.create(backendConfig.getConfig());
                    break;
                case SOLR:
                default:
                    throw new IllegalArgumentException("Can't instantiate " + engine);
            }

        }
        return instance;
    }

    public synchronized void reconfigure(final SearchBackendEngine searchBackend, final Map<String, Object> config) {
        if (searchBackend == getBackend().getEngine()) {
            instance = instance.reconfigure(config);
        }
    }
}