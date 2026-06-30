package org.jenkinsci.plugins.lucene.search;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.listeners.ItemListener;
import java.io.IOException;
import javax.inject.Inject;
import jenkins.model.Jenkins;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;

@Extension
public class FreeTextItemListener extends ItemListener {

    private static final Logger LOG = Logger.getLogger(FreeTextItemListener.class.getName());

    @Inject
    SearchBackendManager searchBackendManager;

    @Override
    public void onDeleted(Item item) {
        LOG.fine("onDeleted: item=" + item.getFullName());
        try {
            searchBackendManager.deleteJob(item.getFullName());
            LOG.fine("onDeleted: deleted OK item=" + item.getFullName());
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "When deleting the job index: ", e);
        }
    }

    @Override
    public void onRenamed(Item item, String oldName, String newName) {
        if (!(item instanceof Job)) {
            return;
        }
        LOG.fine("onRenamed: old=" + oldName + " new=" + newName + " item=" + item.getFullName());
        try {
            String oldFullName;
            if (item.getParent() instanceof Jenkins) {
                oldFullName = oldName;
            } else {
                oldFullName = item.getParent().getFullName() + "/" + oldName;
            }
            searchBackendManager.renameJob(oldFullName, (Job<?, ?>) item);
            LOG.fine("onRenamed: renamed OK old=" + oldFullName + " new=" + item.getFullName());
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "When renaming the job index: ", e);
        }
    }

    @Override
    public void onLocationChanged(Item item, String oldFullName, String newFullName) {
        if (!(item instanceof Job)) {
            return;
        }
        LOG.fine("onLocationChanged: old=" + oldFullName + " new=" + newFullName + " item=" + item.getFullName());
        try {
            searchBackendManager.renameJob(oldFullName, (Job<?, ?>) item);
            LOG.fine("onLocationChanged: moved OK old=" + oldFullName + " new=" + newFullName);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "When moving the job index: ", e);
        }
    }
}
