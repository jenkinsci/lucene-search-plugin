package org.jenkinsci.plugins.lucene.search.databackend;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Run;
import hudson.search.SearchResult;
import hudson.search.SuggestedItem;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import jenkins.model.Jenkins;

import org.apache.log4j.Logger;
import org.jenkinsci.plugins.lucene.search.FreeTextSearchItemImplementation;
import org.jenkinsci.plugins.lucene.search.SearchResultImpl;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendConfiguration;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendEngine;

@Extension
public class SearchBackendManager {
    private static final Logger LOG = Logger.getLogger(SearchBackendManager.class);

    private transient SearchBackend<?> instance;
    private transient List<SearchFieldDefinition> cachedFieldDefinitions;

    @Inject
    private transient SearchBackendConfiguration backendConfig;

    @SuppressWarnings("rawtypes")
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
        switch (searchBackend) {
        case LUCENE:
            if (instance instanceof LuceneSearchBackend) {
                instance.reconfigure(config);
            } else {
                if (instance != null) {
                    instance.close();
                }
                instance = LuceneSearchBackend.create(backendConfig.getConfig());
            }

            break;
        case SOLR:
            if (instance instanceof SolrSearchBackend) {
                instance.reconfigure(config);
            } else {
                if (instance != null) {
                    instance.close();
                }

                instance = SolrSearchBackend.create(backendConfig.getConfig());
            }
            break;
        default:
            throw new IllegalArgumentException("Can't instantiate " + searchBackend);
        }
    }

    @SuppressWarnings("unchecked")
    public List<FreeTextSearchItemImplementation> getHits(String query, boolean includeHighlights) {
        List<FreeTextSearchItemImplementation> hits = getBackend().getHits(query, includeHighlights);
        if (backendConfig.isUseSecurity()) {
            Jenkins jenkins = Jenkins.getInstance();
            Iterator<FreeTextSearchItemImplementation> iter = hits.iterator();
            while (iter.hasNext()) {
                FreeTextSearchItemImplementation searchItem = iter.next();
                Item item = jenkins.getItem(searchItem.getProjectName());
                if (item == null) {
                    iter.remove();
                }
            }
        }
        return hits;
    }

    public SearchResult getSuggestedItems(String query) {
        SearchResultImpl result = new SearchResultImpl();
        for (FreeTextSearchItemImplementation item : getHits(query, false)) {
            result.add(new SuggestedItem(item));
        }
        return result;
    }

    public void removeBuild(Run<?, ?> run) {
        getBackend().removeBuild(run);
    }

    public void storeBuild(Run<?, ?> run) throws IOException {
        getBackend().storeBuild(run, null);
    }

    public void rebuildDatabase(ManagerProgress progress, int maxWorkers) {
        try {
            getBackend().rebuildDatabase(progress, maxWorkers);
            progress.jobComplete();
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