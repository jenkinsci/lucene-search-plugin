package org.jenkinsci.plugins.lucene.search;

import javax.inject.Inject;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;
import org.apache.log4j.Logger;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;

import java.io.IOException;

@Extension
public class FreeTextItemListener extends ItemListener {

    Logger logger = Logger.getLogger(FreeTextItemListener.class);

    @Inject
    SearchBackendManager searchBackendManager;

    @Override
    public void onDeleted(Item item) {
        try {
            searchBackendManager.deleteJob(item.getFullName());
        } catch (IOException e) {
            logger.error("When deleting the job index: ", e);
        }
    }
}
