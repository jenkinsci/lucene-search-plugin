package org.jenkinsci.plugins.lucene.search.databackend;

import java.util.HashSet;
import java.util.Locale;

public class CaseInsensitiveHashSet extends HashSet<String> {
    private static final long serialVersionUID = 6570134740069883156L;
    private final Locale locale;

    public CaseInsensitiveHashSet(Locale locale) {
        this.locale = locale;
    }

    public CaseInsensitiveHashSet() {
        this(Locale.US);
    }

    @Override
    public boolean add(String s) {
        return super.add(s.toLowerCase(locale));
    }

    @Override
    public boolean contains(Object o) {
        return super.contains(o.toString().toLowerCase(locale));
    }
}
