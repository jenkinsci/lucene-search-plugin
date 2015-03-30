package org.jenkinsci.plugins.lucene.search.databackend;

import org.jenkinsci.plugins.lucene.search.Field;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class LuceneSearchBackendTest {
    @Test
    public void assertAllFieldsAreMapped() {
        for (Field f : Field.values()) {
            assertTrue("Field: " + f + " not found", LuceneSearchBackend.FIELD_TYPE_MAP.containsKey(f));
        }

    }
}
