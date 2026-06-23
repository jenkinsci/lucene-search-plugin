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

  @Inject SearchBackendManager searchBackendManager;

  @Override
  public void onDeleted(Item item) {
    try {
      searchBackendManager.deleteJob(item.getFullName());
    } catch (IOException e) {
      logger.error("When deleting the job index: ", e);
    }
  }

  @Override
  public void onRenamed(Item item, String oldName, String newName) {
    if (!(item instanceof Job)) {
      return;
    }
    try {
      String oldFullName;
      if (item.getParent() instanceof Jenkins) {
        oldFullName = oldName;
      } else {
        oldFullName = item.getParent().getFullName() + "/" + oldName;
      }
      searchBackendManager.renameJob(oldFullName, (Job<?, ?>) item);
    } catch (IOException e) {
      logger.error("When renaming the job index: ", e);
    }
  }

  @Override
  public void onLocationChanged(Item item, String oldFullName, String newFullName) {
    if (!(item instanceof Job)) {
      return;
    }
    try {
      searchBackendManager.renameJob(oldFullName, (Job<?, ?>) item);
    } catch (IOException e) {
      logger.error("When moving the job index: ", e);
    }
  }
}
