package org.jenkinsci.plugins.lucene.search;

import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Run;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import org.apache.log4j.Logger;
import org.jenkinsci.plugins.lucene.search.databackend.SearchBackendManager;

import javax.inject.Inject;
import java.io.IOException;


@Extension
public class FreeTextSaveableListener extends SaveableListener {

    Logger logger = Logger.getLogger(FreeTextSaveableListener.class);

    @Inject
    SearchBackendManager searchBackendManager;

    @Override
    public void onChange(Saveable o, XmlFile file) {
        if (o instanceof Run) {
            Run run = (Run) o;
            try {
                searchBackendManager.removeBuild(run);
                searchBackendManager.storeBuild(run);
            } catch (IOException e) {
                logger.error("update index failed: ", e);
            }
        }
    }

}
