package org.jenkinsci.plugins.lucene.search.databackend;

import hudson.model.Job;
import java.util.LinkedList;
import java.util.List;
import org.apache.http.util.TextUtils;

public class ManagerProgress extends Progress {

  private List<Progress> history = new LinkedList<>();

  private Progress currentProject;

  private long processedItems = 0;

  public void jobComplete() {
    if (currentProject != null) {
      currentProject.setSuccessfullyCompleted();
      currentProject.setFinished();
      setProcessedItems(getProcessedItems() + currentProject.getMax());
      this.setElapsedTime(System.currentTimeMillis() - startTime);
    }
  }

  @Override
  public void assertNoErrors() throws Exception {
    super.assertNoErrors();
    if (currentProject != null) {
      currentProject.assertNoErrors();
    }
  }

  public String getReasonsAsString() {
    if (!TextUtils.isEmpty(super.getReasonMessage())) {
      return super.getReasonMessage();
    } else if (currentProject != null) {
      return currentProject.getReasonMessage();
    }
    return "";
  }

  @Override
  public void completedWithErrors(Exception e) {
    super.completedWithErrors(e);
    if (currentProject != null) {
      currentProject.completedWithErrors(e);
      currentProject.setFinished();
      this.getHistory().add(currentProject);
    }
  }

  public Progress beginCleanJob() {
    incCurrent();
    currentProject = new Progress("clean all");
    this.getHistory().add(currentProject);
    return currentProject;
  }

  public Progress beginJob(Job<?, ?> project) {
    StringBuilder builder = new StringBuilder();
    if (!project.getParent().getDisplayName().equalsIgnoreCase("jenkins")) {
      builder.append(project.getParent().getFullName() + " >> ");
    }
    builder.append(project.getName());
    incCurrent();
    currentProject = new Progress(builder.toString());
    this.getHistory().add(currentProject);
    return currentProject;
  }

  public List<Progress> getHistory() {
    return history;
  }

  public long getProcessedItems() {
    return processedItems;
  }

  public void setProcessedItems(long processedItems) {
    this.processedItems = processedItems;
  }
}
