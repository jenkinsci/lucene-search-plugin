package org.jenkinsci.plugins.lucene.search.databackend;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.search.Collector;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Scorer;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class DistinctCollector extends Collector {

    private final Set<String> field;
    private final String fieldName;
    private final IndexSearcher searcher;
    private final Set<String> distinctData = new HashSet<String>();

    public DistinctCollector(String fieldName, IndexSearcher searcher) {
        this.searcher = searcher;
        this.field = Collections.singleton(fieldName);
        this.fieldName = fieldName;
    }

    @Override
    public void setScorer(Scorer scorer) throws IOException {
    }

    @Override
    public void collect(int doc) throws IOException {
        Document document = searcher.doc(doc, field);
        String projectName = document.get(fieldName);
        distinctData.add(projectName);
    }

    @Override
    public void setNextReader(AtomicReaderContext context) throws IOException {
    }

    @Override
    public boolean acceptsDocsOutOfOrder() {
        return true;
    }

    public Set<String> getDistinctData() {
        return distinctData;
    }
}
