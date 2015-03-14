package org.jenkinsci.plugins.lucene.search;

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.search.SearchResult;
import hudson.search.SuggestedItem;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.jenkinsci.plugins.lucene.search.config.SearchBackendConfiguration;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;
import org.jenkinsci.plugins.lucene.search.databackend.LuceneSearchBackend;
import org.jenkinsci.plugins.lucene.search.databackend.ManagerProgress;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackend;
import org.jenkinsci.plugins.lucene.search.databackend.SolrSearchBackend;

@Extension
public class SearchBackendManager {
    private transient SearchBackend instance;

    @Inject
    private transient SearchBackendConfiguration backendConfig;

    private synchronized SearchBackend getBackend() {
        if (instance == null) {
            SearchBackendEngine engine = backendConfig.getSearchBackendEngine();
            switch (engine) {
            case LUCENE:
                instance = LuceneSearchBackend.create(backendConfig.getConfig());
                break;
            case SOLR:
                instance = SolrSearchBackend.create(backendConfig.getConfig());
                break;
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

    public List<FreeTextSearchItemImplementation> getHits(String query, boolean includeHighlights) {
        return getBackend().getHits(query, includeHighlights);
    }

    public SearchResult getSuggestedItems(String query) {
        SearchResultImpl result = new SearchResultImpl();
        for (FreeTextSearchItemImplementation item : getHits(query, false)) {
            result.add(new SuggestedItem(item));
        }
        return result;
    }

    public void removeBuild(AbstractBuild<?, ?> build) {
        getBackend().removeBuild(build);
    }

    public void storeBuild(AbstractBuild<?, ?> build) throws IOException {
        getBackend().storeBuild(build);
    }

    public void rebuildDatabase(ManagerProgress progress) {
        getBackend().rebuildDatabase(progress);
    }
}