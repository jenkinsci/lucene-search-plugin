package org.jenkinsci.plugins.lucene.search.databackend;

import org.apache.lucene.search.IndexSearcher;

class LengthLimitedDistinctCollector extends DistinctCollector {

    private final int maxLength;

    public LengthLimitedDistinctCollector(String fieldName, IndexSearcher searcher, int maxLength) {
        super(fieldName, searcher);
        if (maxLength < 4) {
            throw new IllegalArgumentException("Length must be at least 4");
        }
        this.maxLength = maxLength;
    }

    @Override
    protected void addData(String fieldValue) {
        if (fieldValue != null) {
            if (fieldValue.length() > maxLength) {
                // ellipsize
                super.addData(fieldValue.substring(0, maxLength - 3) + "...");
            } else {
                super.addData(fieldValue);
            }
        }
    }
}
