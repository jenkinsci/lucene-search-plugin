package org.jenkinsci.plugins.lucene.search.databackend;

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.search.SearchResult;
import hudson.search.SuggestedItem;
import org.apache.log4j.Logger;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;
import org.jenkinsci.plugins.lucene.search.SearchResultImpl;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendConfiguration;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Extension
public class SearchBackendManager {
    private static final Logger LOG = Logger.getLogger(SearchBackendManager.class);

    private transient SearchBackend instance;
    private transient List<SearchFieldDefinition> cachedFieldDefinitions;

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
        getBackend().storeBuild(build, null);
    }

    public void rebuildDatabase(ManagerProgress progress, int maxWorkers) {
        try {
            getBackend().rebuildDatabase(progress, maxWorkers);
        } catch (RuntimeException e) {
            progress.withReason(e);
            progress.setReasonMessage(e.toString());
            LOG.error("Failed rebuilding search database", e);
            throw e;
        } finally {
            progress.setFinished();
        }
    }

    public List<SearchFieldDefinition> getSearchFieldDefinitions() throws IOException {
        return getSearchFieldDefinitions(false);
    }

    public synchronized List<SearchFieldDefinition> getSearchFieldDefinitions(boolean forceRefresh) throws IOException {
        if (cachedFieldDefinitions == null || forceRefresh) {
            cachedFieldDefinitions = Collections.unmodifiableList(getBackend().getAllFieldDefinitions());
        }
        return cachedFieldDefinitions;
    }
}