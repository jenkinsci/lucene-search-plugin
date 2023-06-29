package org.jenkinsci.plugins.lucene.search.artifact;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.remoting.VirtualChannel;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.LogTaskListener;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import jenkins.MasterToSlaveFileCallable;
import org.apache.commons.io.IOUtils;
import org.apache.log4j.Logger;
import org.jenkinsci.remoting.RoleChecker;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Supplies the methods and configuration data needed to get index artifacts. Doesn't do anything in
 * the perform step, but should conceptually be a post-build step.
 */
public class ArtifactIndexer extends Recorder {

  private static final Logger LOGGER = Logger.getLogger(ArtifactIndexer.class);

  /** Comma- or space-separated list of patterns of files/directories to be archived. */
  private final String artifacts;

  /** Possibly null 'excludes' pattern as in Ant. */
  private final String excludes;

  /** Encoding of files, e.g. UTF8 */
  private final String charset;

  @DataBoundConstructor
  public ArtifactIndexer(String artifacts, String excludes, String charset) {
    this.artifacts = artifacts;
    this.excludes = excludes;
    this.charset = charset;
    Charset.forName(charset);
  }

  @Override
  public BuildStepMonitor getRequiredMonitorService() {
    return BuildStepMonitor.NONE;
  }

  @Override
  public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
    return true;
  }

  public String getExcludes() {
    return excludes;
  }

  public String getArtifacts() {
    return artifacts;
  }

  public String getIndexableData(AbstractBuild<?, ?> build) {
    FilePath ws = build.getWorkspace();
    if (ws != null) { // slave down?
      try {
        StringBuilder sb = new StringBuilder();
        LogTaskListener listener =
            new LogTaskListener(
                java.util.logging.Logger.getLogger(ArtifactIndexer.class.getName()), Level.INFO);
        String artifacts = build.getEnvironment(listener).expand(this.artifacts);
        Map<String, String> fileData = ws.act(new DumpFiles(artifacts, excludes, charset));
        for (Map.Entry<String, String> entry : fileData.entrySet()) {
          sb.append(entry.getKey()).append(" ").append(entry.getValue()).append(" ");
        }
        return sb.toString();
      } catch (IOException e) {
        LOGGER.error("Couldn't get artifacts for search database", e);
      } catch (InterruptedException e) {
        LOGGER.error("Couldn't get artifacts for search database", e);
      }
    }
    return null;
  }

  // The following class is mostly copy-paste from ArtifactArchiver
  private static final class DumpFiles extends MasterToSlaveFileCallable<Map<String, String>> {
    private static final long serialVersionUID = 1;
    private final String includes;
    private final String excludes;
    private final String charset;

    DumpFiles(String includes, String excludes, String charset) {
      this.includes = includes;
      this.excludes = excludes;
      this.charset = charset;
    }

    @Override
    public Map<String, String> invoke(File basedir, VirtualChannel channel)
        throws IOException, InterruptedException {
      Map<String, String> r = new LinkedHashMap<String, String>();
      for (String f :
          Util.createFileSet(basedir, includes, excludes)
              .getDirectoryScanner()
              .getIncludedFiles()) {
        f = f.replace(File.separatorChar, '/');
        r.put(f, IOUtils.toString(new File(f).toURI(), Charset.forName(charset)));
      }
      return r;
    }

    @Override
    public void checkRoles(RoleChecker checker) throws SecurityException {
      // TODO Auto-generated method stub

    }
  }

  @Extension
  public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
    @Override
    public String getDisplayName() {
      return "Search artifact contents";
    }

    @Override
    public boolean isApplicable(
        @SuppressWarnings("rawtypes") Class<? extends AbstractProject> jobType) {
      return true;
    }
  }
}
