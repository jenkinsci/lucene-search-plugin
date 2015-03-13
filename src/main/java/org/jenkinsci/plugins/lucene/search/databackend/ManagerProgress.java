package org.jenkinsci.plugins.lucene.search.databackend;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedList;
import java.util.List;

public class ManagerProgress extends Progress {

    private List<Progress> history = new LinkedList<Progress>();

    private Progress currentProject;

    private Progress deletedJobsCleanProgress;
    private Progress deletedBuildsCleanProgress;
    private Progress rebuildProgress;

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("Currently processing ");
        builder.append(currentProject.getName() + ". Parsing project ");
        builder.append(getCurrent());
        builder.append(" out of ");
        builder.append(getMax());
        builder.append("<br />\n");

        if (deletedJobsCleanProgress != null && !deletedJobsCleanProgress.isFinished()) {
            builder.append("Cleaning projects ...");
            builder.append("<br />\n");
        }

        if (deletedBuildsCleanProgress != null && !deletedBuildsCleanProgress.isFinished()) {
            builder.append("Cleaning builds ...");
            builder.append("<br />\n");
        }

        if (rebuildProgress != null && !rebuildProgress.isFinished()) {
            builder.append("Rebuilding ...");
            builder.append("<br />\n");
        }

        for (Progress p : history) {
            builder.append("<b>History</b><br/>\n");
            builder.append(p.getName());
            builder.append(" took ");
            builder.append(p.elapsedTime());
            builder.append(" ms to process");
            if (p.getReason() != null) {
                builder.append(" but exited with the error ");
                builder.append(p.getReason().getMessage());
            } else {
                builder.append(" completed successfully");
            }
            builder.append("<br/>}n");
        }

        return builder.toString();
    }

    public void setComplete() {
        if (this.currentProject != null) {

            currentProject.setFinished();

            this.history.add(currentProject);

        }
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

    @Override
    public void setError(Throwable e) {
        super.setError(e);
        currentProject.setFinished();
        this.history.add(currentProject);
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

    public void next(String displayName) {
        this.currentProject = new Progress(displayName);
        this.setCurrent(this.getCurrent() + 1);
    }
}