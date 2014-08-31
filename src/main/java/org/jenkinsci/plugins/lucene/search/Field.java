package org.jenkinsci.plugins.lucene.search;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public enum Field {
    ID("id", DefaultSearchable.FALSE, Numeric.TRUE, Persist.TRUE), //
    PROJECT_NAME("projectname", Persist.TRUE), //
    PROJECT_DISPLAY_NAME("projectdisplayname", Persist.TRUE), //
    BUILD_NUMBER("buildnumber", Numeric.TRUE), //
    RESULT("result", Persist.TRUE), //
    DURATION("duration"), //
    START_TIME("starttime", DefaultSearchable.FALSE, Numeric.TRUE), //
    BUILT_ON("builton"), //
    START_CAUSE("startcause"), //
    BALL_COLOR("color", DefaultSearchable.FALSE, Persist.TRUE), //
    CONSOLE("console", Persist.TRUE); //

    public final String fieldName;
    public final boolean defaultSearchable;
    public final boolean numeric;
    public final boolean persist;
    
    @SuppressWarnings("rawtypes")
    private Field(String fieldName, Enum... e) {
        List<Enum> es = Arrays.asList(e);
        defaultSearchable = !es.contains(DefaultSearchable.FALSE);
        numeric = es.contains(Numeric.TRUE);
        persist = es.contains(Numeric.TRUE);
        this.fieldName = fieldName;
    }

    private static Map<String, Field> index;

    public static Field getIndex(String fieldName) {
        if (index == null) {
            Map<String, Field> indexReverseLookup = new HashMap<String, Field>();
            for (Field idx : Field.values()) {
                indexReverseLookup.put(idx.fieldName, idx);
            }
            index = indexReverseLookup;
        }
        return index.get(fieldName);
    }
    
    private enum Persist {
        TRUE;
    }

    private enum DefaultSearchable {
        FALSE;
    }

    private enum Numeric {
        TRUE;
    }

}


