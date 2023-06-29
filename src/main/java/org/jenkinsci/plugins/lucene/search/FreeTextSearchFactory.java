package org.jenkinsci.plugins.lucene.search;

import com.google.inject.Inject;
import hudson.Extension;
import hudson.search.Search;
import hudson.search.SearchFactory;
import hudson.search.SearchableModelObject;
import org.jenkinsci.plugins.lucene.search.config.SearchBackendConfiguration;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;

@Extension
public class FreeTextSearchFactory extends SearchFactory {
  @Inject SearchBackendManager manager;

  @Inject private transient SearchBackendConfiguration backendConfig;

  @Override
  public Search createFor(final SearchableModelObject owner) {
    if (backendConfig.isLuceneSearchEnabled()) {
      return new FreeTextSearch(manager);
    }
    return null;
  }
}
