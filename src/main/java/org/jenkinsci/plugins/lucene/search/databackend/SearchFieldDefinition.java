package org.jenkinsci.plugins.lucene.search.databackend;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

public class SearchFieldDefinition {
    private final String fieldName;
    private final boolean caseSensitive;
    private final Collection<String> values;

    public SearchFieldDefinition(String fieldName, boolean caseSensitive, Collection<String> values) {
        this.fieldName = fieldName;
        this.caseSensitive = caseSensitive;
        this.values = Collections.unmodifiableCollection(values);
    }

    public String getFieldName() {
        return fieldName;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public Collection<String> getValues() {
        return values;
    }

    public String getExamples(int nrExamples) {
        if (nrExamples < 1) {
            throw new IllegalArgumentException("Cannot use so low number of examples");
        }
        int i = 0;
        Iterator<String> iter = values.iterator();
        StringBuilder sb = new StringBuilder();
        while (iter.hasNext() && i++ < nrExamples) {
            sb.append(", ").append(iter.next());
        }
        return sb.substring(2);
    }
}
