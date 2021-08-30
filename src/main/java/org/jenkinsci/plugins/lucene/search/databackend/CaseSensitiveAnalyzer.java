package org.jenkinsci.plugins.lucene.search.databackend;

import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.StopFilter;
import org.apache.lucene.analysis.standard.StandardFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.util.CharArraySet;
import org.apache.lucene.analysis.util.StopwordAnalyzerBase;

import java.io.IOException;
import java.io.Reader;

public class CaseSensitiveAnalyzer extends StopwordAnalyzerBase {

    public static final int DEFAULT_MAX_TOKEN_LENGTH = 255;

    public CaseSensitiveAnalyzer() {
        super(CharArraySet.EMPTY_SET);
    }

    @Override
    protected TokenStreamComponents createComponents(final String fieldName) {
        final StandardTokenizer src = new StandardTokenizer();
        src.setMaxTokenLength(DEFAULT_MAX_TOKEN_LENGTH);

        TokenStream tok = new StandardFilter(src);
        tok = new StopFilter(tok, this.stopwords);
        return new TokenStreamComponents(src, tok) {
            @Override
            protected void setReader(final Reader reader) throws IOException {
                src.setMaxTokenLength(DEFAULT_MAX_TOKEN_LENGTH);
                super.setReader(reader);
            }
        };
    }
}
