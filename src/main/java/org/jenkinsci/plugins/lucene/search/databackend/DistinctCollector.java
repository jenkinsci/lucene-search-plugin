package org.jenkinsci.plugins.lucene.search.databackend;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.LeafCollector;
import org.apache.lucene.search.Scorer;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class DistinctCollector implements Collector {

    private final Set<String> field;
    private final String fieldName;
    private final IndexSearcher searcher;
    private final Set<String> distinctData = new LinkedHashSet<String>();

    public DistinctCollector(String fieldName, IndexSearcher searcher) {
        this.searcher = searcher;
        this.field = Collections.singleton(fieldName);
        this.fieldName = fieldName;
    }

    protected void addData(String fieldValue) {
        distinctData.add(fieldValue);
    }

    public Set<String> getDistinctData() {
        return distinctData;
    }

    @Override
    public LeafCollector getLeafCollector(LeafReaderContext context) throws IOException {
        return new LeafCollector() {

            // ignore scorer
            public void setScorer(Scorer scorer) throws IOException {
            }

            public void collect(int doc) throws IOException {
                Document document = searcher.doc(doc, field);
                String fieldValue = document.get(fieldName);
                addData(fieldValue);
            }

        };
    }

    @Override
    public boolean needsScores() {
        return false;
    }
}
