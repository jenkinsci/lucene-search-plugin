package org.jenkinsci.plugins.lucene.search;

import hudson.model.Run;
import hudson.model.Job;
import hudson.model.Cause;
import hudson.model.Result;
import hudson.scm.ChangeLogSet;
import hudson.tasks.Publisher;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.jenkinsci.plugins.lucene.search.artifact.ArtifactIndexer;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public enum Field {
    ID("id", DefaultSearchable.FALSE, Numeric.TRUE, Persist.TRUE) {
        @Override
        public String getValue(Run<?, ?> build) {
            return build.getId();
        }
    },
    PROJECT_NAME("projectname", Persist.TRUE) {
        public String getValue(final Run<?, ?> build) {
            StringBuilder builder = new StringBuilder();
            if (!build.getParent().getParent().getDisplayName().equalsIgnoreCase("jenkins")) {
                builder.append(build.getParent().getParent().getFullName() + "/");
            }
            builder.append(build.getParent().getName());
            return builder.toString();
        }
    },

    PROJECT_DISPLAY_NAME("projectdisplayname", Persist.TRUE) {
        @Override
        public String getValue(Run<?, ?> build) {
            StringBuilder builder = new StringBuilder();
            if (!build.getParent().getParent().getDisplayName().equalsIgnoreCase("jenkins")) {
                builder.append(build.getParent().getParent().getDisplayName() + "/");
            }
            builder.append(build.getParent().getDisplayName());
            return builder.toString();
        }
    },

    BUILD_NUMBER("buildnumber", Numeric.TRUE, Persist.TRUE) {
        @Override
        public Integer getValue(Run<?, ?> build) {
            return build.getNumber();
        }
    },

    RESULT("result", Persist.TRUE) {
        @Override
        public Result getValue(Run<?, ?> build) {
            return build.getResult();
        }
    },

    DURATION("duration", DefaultSearchable.FALSE) {
        @Override
        public Long getValue(Run<?, ?> build) {
            return build.getDuration();
        }
    },

    START_TIME("starttime", DefaultSearchable.FALSE, Numeric.TRUE, Persist.TRUE) {
        @Override
        public Long getValue(Run<?, ?> build) {
            return build.getStartTimeInMillis();
        }
    },
    BUILT_ON("builton") {
        @Override
        public String getValue(Run<?, ?> build) {
            return build.getExecutor().getDisplayName();
        }
    },
    START_CAUSE("startcause") {
        @Override
        public String getValue(Run<?, ?> build) {
            StringBuilder shortDescriptions = new StringBuilder();
            for (Cause cause : build.getCauses()) {
                shortDescriptions.append(" ").append(cause.getShortDescription());
            }
            return shortDescriptions.toString();
        }
    },
    BALL_COLOR("color", DefaultSearchable.FALSE, Persist.TRUE) {
        @Override
        public String getValue(Run<?, ?> build) {
            return build.getIconColor().name();
        }
    },

    CONSOLE("console", Persist.TRUE) {
        @Override
        public String getValue(Run<?, ?> build) {
            try {
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                build.getLogText().writeLogTo(0, byteArrayOutputStream);
                String consoleOutput = byteArrayOutputStream.toString();
                return consoleOutput;
            } catch (IOException e) {
                // Probably won't happen, but don't silently swallow exceptions at least
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    },

    CHANGE_LOG("changelog", Persist.TRUE) {
        @Override
        public Object getValue(Run<?, ?> build) {
            //            ChangeLogSet<? extends ChangeLogSet.Entry> changeSet = build.getChangeSet();
            //            StringBuilder sb = new StringBuilder();
            //            if (changeSet != null) {
            //                for (ChangeLogSet.Entry entry : build.getChangeSet()) {
            //                    sb.append("author:").append(entry.getAuthor()).append('\n');
            //                    sb.append("commitid:").append(entry.getCommitId()).append('\n');
            //                    sb.append("message:").append(entry.getMsg()).append('\n');
            //                    for (String path : entry.getAffectedPaths()) {
            //                        sb.append(path).append('\n');
            //                    }
            //                }
            //            }
            //            return sb.toString();
            return "";
        }
    },

    ARTIFACTS("artifacts", Persist.TRUE) {
        @Override
        public Object getValue(Run<?, ?> build) {
            //            Job<?, ?> p = build.getParent();
            //            for (Publisher publisher : p.getPublishersList()) {
            //                if (publisher instanceof ArtifactIndexer) {
            //                    ArtifactIndexer ai = (ArtifactIndexer) publisher;
            //                    return ai.getIndexableData(build);
            //                }
            //            }
            return "";
        }
    };

    private static Map<String, Field> index;
    public final String fieldName;
    public final boolean defaultSearchable;
    public final boolean numeric;
    public final boolean persist;

    @SuppressWarnings("rawtypes")
    Field(String fieldName, Enum... e) {
        List<Enum> es = Arrays.asList(e);
        defaultSearchable = !es.contains(DefaultSearchable.FALSE);
        numeric = es.contains(Numeric.TRUE);
        persist = es.contains(Persist.TRUE);
        this.fieldName = fieldName;
    }

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

    public abstract Object getValue(final Run<?, ?> build);

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
