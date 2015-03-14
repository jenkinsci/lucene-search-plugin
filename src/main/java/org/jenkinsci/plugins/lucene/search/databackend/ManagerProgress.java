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

    public void setComplete() {
        if (this.getCurrentProject() != null) {
            getCurrentProject().setSuccessfullyCompleted();
            getCurrentProject().setFinished();

            this.getHistory().add(getCurrentProject());

        }
    }

    @Override
    public void assertNoErrors() throws Throwable {
        super.assertNoErrors();
        if (getDeletedJobsCleanProgress() != null) {
            getDeletedJobsCleanProgress().assertNoErrors();
        }

        if (getDeletedBuildsCleanProgress() != null) {
            getDeletedBuildsCleanProgress().assertNoErrors();
        }
        if (getRebuildProgress() != null) {
            getRebuildProgress().assertNoErrors();
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
    public void completedWithErrors(Throwable e) {
        super.completedWithErrors(e);
        getCurrentProject().completedWithErrors(e);
        getCurrentProject().setFinished();
        this.getHistory().add(getCurrentProject());
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
        setRebuildProgress(null);
        setDeletedBuildsCleanProgress(null);
    }

    public void next(String displayName) {
        this.setCurrentProject(new Progress(displayName));
        this.setCurrent(this.getCurrent() + 1);
    }

    public List<Progress> getHistory() {
        return history;
    }

    public void setHistory(List<Progress> history) {
        this.history = history;
    }

    public Progress getCurrentProject() {
        return currentProject;
    }

    public void setCurrentProject(Progress currentProject) {
        this.currentProject = currentProject;
    }

    public void setDeletedJobsCleanProgress(Progress deletedJobsCleanProgress) {
        this.deletedJobsCleanProgress = deletedJobsCleanProgress;
    }

    public void setDeletedBuildsCleanProgress(Progress deletedBuildsCleanProgress) {
        this.deletedBuildsCleanProgress = deletedBuildsCleanProgress;
    }

    public void setRebuildProgress(Progress rebuildProgress) {
        this.rebuildProgress = rebuildProgress;
    }
}