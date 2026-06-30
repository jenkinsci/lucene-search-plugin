package org.jenkinsci.plugins.lucene.search;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.listeners.ItemListener;
import java.io.IOException;
import javax.inject.Inject;
import jenkins.model.Jenkins;
import org.apache.log4j.Logger;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;

@Extension
public class FreeTextItemListener extends ItemListener {

    Logger logger = Logger.getLogger(FreeTextItemListener.class);

    @Inject
    SearchBackendManager searchBackendManager;

    @Override
    public void onDeleted(Item item) {
        logger.debug("onDeleted: item=" + item.getFullName());
        try {
            searchBackendManager.deleteJob(item.getFullName());
            logger.debug("onDeleted: deleted OK item=" + item.getFullName());
        } catch (IOException e) {
            logger.error("When deleting the job index: ", e);
        }
    }

    @Override
    public void onRenamed(Item item, String oldName, String newName) {
        if (!(item instanceof Job)) {
            return;
        }
        logger.debug("onRenamed: old=" + oldName + " new=" + newName + " item=" + item.getFullName());
        try {
            String oldFullName;
            if (item.getParent() instanceof Jenkins) {
                oldFullName = oldName;
            } else {
                oldFullName = item.getParent().getFullName() + "/" + oldName;
            }
            searchBackendManager.renameJob(oldFullName, (Job<?, ?>) item);
            logger.debug("onRenamed: renamed OK old=" + oldFullName + " new=" + item.getFullName());
        } catch (IOException e) {
            logger.error("When renaming the job index: ", e);
        }
    }

    @Override
    public void onLocationChanged(Item item, String oldFullName, String newFullName) {
        if (!(item instanceof Job)) {
            return;
        }
        logger.debug("onLocationChanged: old=" + oldFullName + " new=" + newFullName + " item=" + item.getFullName());
        try {
            searchBackendManager.renameJob(oldFullName, (Job<?, ?>) item);
            logger.debug("onLocationChanged: moved OK old=" + oldFullName + " new=" + newFullName);
        } catch (IOException e) {
            logger.error("When moving the job index: ", e);
        }
    }
}
