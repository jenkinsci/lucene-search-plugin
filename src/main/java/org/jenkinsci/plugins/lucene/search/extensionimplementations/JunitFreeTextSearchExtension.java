package org.jenkinsci.plugins.lucene.search.extensionimplementations;

import hudson.Extension;
import hudson.model.Run;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.junit.CaseResult;

import java.util.List;

import org.jenkinsci.plugins.lucene.search.FreeTextSearchExtension;

@Extension
public class JunitFreeTextSearchExtension extends FreeTextSearchExtension {

    @Override
    public String getKeyword() {
        return "unittest";
    }

    @Override
    public String getTextResult(Run<?, ?> run) {
        List<TestResultAction> actions = run.getActions(TestResultAction.class);
        StringBuilder builder = new StringBuilder();
        for (TestResultAction action : actions) {
            List<CaseResult> failedTests = action.getFailedTests();
            for (CaseResult result : failedTests) {
                builder.append(result.getTitle() + "\n");
                builder.append(result.getErrorDetails() + "\n");
            }
        }
        return builder.toString();
    }

    @Override
    public boolean isPersist() {
        return true;
    }

}
