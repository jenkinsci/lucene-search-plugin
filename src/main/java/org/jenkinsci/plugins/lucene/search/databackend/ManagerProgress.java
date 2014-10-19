package org.jenkinsci.plugins.lucene.search.databackend;

import java.io.PrintWriter;
import java.io.StringWriter;

public class ManagerProgress extends Progress {

    private String currentProjectName = "";

    private Progress deletedJobsCleanProgress;
    private Progress deletedBuildsCleanProgress;
    private Progress rebuildProgress;

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();

        builder.append("Currently processing ");
        builder.append(getCurrentProjectName());
        builder.append(getCurrent());
        builder.append('/');
        builder.append(getMax());
        builder.append("<br />\n");

        if (deletedJobsCleanProgress != null && !deletedJobsCleanProgress.isFinished()) {
            builder.append("Cleaning projects ...");
            builder.append("<br />\n");
        }
        // FIXME

        if (deletedBuildsCleanProgress != null && !deletedBuildsCleanProgress.isFinished()) {
            builder.append("Cleaning builds ...");
            builder.append("<br />\n");
        }

        return builder.toString();
        //Currently processing: Jenkins-Plugin X/Y
        //cleaning ...
        //rebuilding build X outof Y
        //progress.setMaxProject(Jenkins.getInstance().getAllItems(Job.class).size())

        //return String.format("Currently processing %s %d/%d ", );
    }

    @Override
    public void assertNoErrors() throws Throwable {
        super.assertNoErrors();
        if (deletedJobsCleanProgress != null) {
            deletedJobsCleanProgress.assertNoErrors();
        }

        if (deletedBuildsCleanProgress != null) {
            deletedBuildsCleanProgress.assertNoErrors();
        }
        if (rebuildProgress != null) {
            rebuildProgress.assertNoErrors();
        }

    }

    public String getCurrentProjectName() {
        return currentProjectName;
    }

    public void setCurrentProjectName(String currentProjectName) {
        this.currentProjectName = currentProjectName;
    }

    public Progress getDeletedBuildsCleanProgress() {
        if (deletedBuildsCleanProgress == null) {
            deletedBuildsCleanProgress = new Progress();
        }

        return deletedBuildsCleanProgress;
    }

    public String getReasonsAsString() {
        StringWriter writer = new StringWriter();
        PrintWriter printWriter = new PrintWriter(writer);

        addIfNotNull(printWriter, getDeletedJobsCleanProgress());
        addIfNotNull(printWriter, getDeletedBuildsCleanProgress());
        addIfNotNull(printWriter, getRebuildProgress());

        return writer.toString();
    }

    private void addIfNotNull(PrintWriter printWriter, Progress progress) {
        if (progress.getReason() != null) {
            progress.getReason().printStackTrace(printWriter);
        }
    }

    public Progress getDeletedJobsCleanProgress() {
        if (deletedJobsCleanProgress == null) {
            deletedJobsCleanProgress = new Progress();
        }

        return deletedJobsCleanProgress;
    }

    public Progress getRebuildProgress() {
        if (rebuildProgress == null) {
            rebuildProgress = new Progress();
        }
        return rebuildProgress;
    }

    public void setNewIteration() {
        rebuildProgress = null;
        deletedBuildsCleanProgress = null;
    }
}