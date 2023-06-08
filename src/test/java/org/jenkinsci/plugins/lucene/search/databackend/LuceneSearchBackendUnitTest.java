package org.jenkinsci.plugins.lucene.search.databackend;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class LuceneSearchBackendUnitTest {
    
    @Test
    public void escapingQueryWorks() {
        String[] input = new String[]{
                "job",
            "j:job",
            "job c:job",
            "folder/job c:job",
            "j:folder/job",
            "j:\"this is a job\"",
            "j:folder/job c:something",
            "j:folder/job AND c:something"
        };
        String[] expected = new String[]{
                "job",
                    "j:job",
                    "job c:job",
                    "folder\\/job c:job",
                    "j:folder\\/job",
                    "j:\\\"this is a job\\\"",
                    "j:folder\\/job c:something",
                    "j:folder\\/job AND c:something"
                };
        for (int i = 0; i < input.length; i++) {
            assertEquals(expected[i], LuceneSearchBackend.escapeQuery(input[i]));
        }
    }

}
