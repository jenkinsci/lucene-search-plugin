package org.jenkinsci.plugins.lucene.search;

import java.util.HashMap;
import java.util.Map;

public enum Field {
    CONSOLE("console"), PROJECT_NAME("projectname"), BUILD_NUMBER("buildnumber", true, true), ID("id", false, true), PROJECT_DISPLAY_NAME(
            "projectdisplayname"), RESULT("result"), DURATION("duration", false, true), START_TIME("starttime",
            false, true), BUILT_ON("builton"), START_CAUSE("startcause"), BALL_COLOR("color", false, false);
    public final String fieldName;
    public final boolean defaultSearchable;
    public final boolean numeric;

    private Field(String fieldName) {
        this(fieldName, true, false);
    }

    private Field(String fieldName, boolean defaultSearchable, boolean numeric) {
        this.fieldName = fieldName;
        this.defaultSearchable = defaultSearchable;
        this.numeric = numeric;
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
}
