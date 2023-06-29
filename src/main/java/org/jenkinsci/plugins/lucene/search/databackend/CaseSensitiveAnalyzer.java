package org.jenkinsci.plugins.lucene.search.databackend;

import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.StopwordAnalyzerBase;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardTokenizer;

public class CaseSensitiveAnalyzer extends StopwordAnalyzerBase {

  public static final int DEFAULT_MAX_TOKEN_LENGTH = 255;

  public CaseSensitiveAnalyzer() {
    super(CharArraySet.EMPTY_SET);
  }

  @Override
  protected TokenStreamComponents createComponents(final String fieldName) {
    final StandardTokenizer src = new StandardTokenizer();
    src.setMaxTokenLength(DEFAULT_MAX_TOKEN_LENGTH);

    TokenStream tok = new StopFilter(src, this.stopwords);
    return new TokenStreamComponents(
        r -> {
          src.setMaxTokenLength(DEFAULT_MAX_TOKEN_LENGTH);
          src.setReader(r);
        },
        tok);
  }
}
