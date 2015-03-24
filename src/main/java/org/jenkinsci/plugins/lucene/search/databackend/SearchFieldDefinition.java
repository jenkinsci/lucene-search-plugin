package org.jenkinsci.plugins.lucene.search.databackend;

public class SearchFieldDefinition {
    private final String fieldName;
    private final boolean caseSensitive;
    private final String[] values;

    public SearchFieldDefinition(String fieldName, boolean caseSensitive, String[] values) {
        this.fieldName = fieldName;
        this.caseSensitive = caseSensitive;
        this.values = values;
    }

    public String getFieldName() {
        return fieldName;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public String[] getValues() {
        return values;
    }
}
