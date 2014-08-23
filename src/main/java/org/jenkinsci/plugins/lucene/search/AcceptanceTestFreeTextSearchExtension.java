package org.jenkinsci.plugins.lucene.search;

import hudson.Extension;
import hudson.model.AbstractBuild;
import hudson.model.Action;

import java.awt.*;

@Extension
public class AcceptanceTestFreeTextSearchExtension extends FreeTextSearchExtension {

        /*Summary

               <a>INT-V150 build 45</a>
                    </>
                     Build failed due to CRAP more logs can be found in dslakdjsaljdlajdslkajdlsajsjadjajdlsa
                     Acceptanctest suite nisse/arne/ablala/
                    </i>

               <a>INT-V150 build 47</a>
                    </>dslöaldsadsadsadsadsad dsa dsa dsadsa
                    ds
                    ad sadsadsads adsa
                    dsadsadsa   <b>55566</b>
                    dsadsadsadsadsad
                    s
                    dsadsadsa</i>



                    */

    @Override
    public String getKeyWord() {
        return "acceptancetest";
    }

    @Override
    public String getTextResult(AbstractBuild<?, ?> build) {
        StringBuilder builder = new StringBuilder();
        build.addAction(new FitSummaryAction());
        for (FitSummaryAction action : build.getActions(FitSummaryAction.class)) {
            builder.append(action.getFakeString());
        }
        System.err.println("HELLO KITTY");
        System.err.println(builder.toString());
        return builder.toString();
    }

    public boolean persist() {
        return true;
    }

    // fake for the sake of test
    public static class FitSummaryAction implements Action {
        public String getFakeString() {
            return "NISSE";
        }

        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return null;
        }

        @Override
        public String getUrlName() {
            return null;
        }
    }
}
