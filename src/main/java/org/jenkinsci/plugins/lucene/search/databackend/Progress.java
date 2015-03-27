package org.jenkinsci.plugins.lucene.search.databackend;

public class Progress {

    public enum ProgressState {
        PROCESSING, COMPLETE, COMPLETE_WITH_ERROR
    }

    protected long startTime;
    private long elapsedTime;

    private ProgressState state = ProgressState.PROCESSING;
    private transient Throwable reason;
    private String reasonMessage = "";
    private int max;
    private volatile int current;
    private String name;

    public void assertNoErrors() throws Throwable {
        if (getState() == ProgressState.COMPLETE_WITH_ERROR) {
            throw reason;
        }
    }

    public Progress() {
        startTime = System.currentTimeMillis();
    }

    public Progress(String name) {
        this.setName(name);
        startTime = System.currentTimeMillis();
    }

    public void completedWithErrors(Throwable reason) {
        state = ProgressState.COMPLETE_WITH_ERROR;
        this.withReason(reason);
        reasonMessage = reason.getMessage();
    }

    /**
     * Work complete. Does NOT imply success, only that no more processing will
     * be done
     */
    public void setFinished() {
        if (state == ProgressState.PROCESSING) {
            state = ProgressState.COMPLETE_WITH_ERROR;
        }
        setElapsedTime(System.currentTimeMillis() - startTime);
    }

    /**
     * Work has been successfully completed. Current will be one less than max.
     */
    public void setSuccessfullyCompleted() {
        state = ProgressState.COMPLETE;
    }

    public ProgressState getState() {
        return state;
    }

    public boolean isFinished() {
        return state == ProgressState.COMPLETE || state == ProgressState.COMPLETE_WITH_ERROR;
    }

    public Throwable getReason() {
        return reason;
    }

    public int getMax() {
        return max;
    }

    public void setMax(int max) {
        this.max = max;
    }

    public int getCurrent() {
        return current;
    }

    public void setCurrent(int current) {
        this.current = current;
    }

    public void incCurrent() {
        this.current++;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getElapsedTime() {
        return elapsedTime;
    }

    public void setElapsedTime(long elapsedTime) {
        this.elapsedTime = elapsedTime;
    }

    public void withReason(Throwable reason) {
        this.reason = reason;
    }

    public String getReasonMessage() {
        return reasonMessage;
    }

    public void setReasonMessage(String reasonMessage) {
        this.reasonMessage = reasonMessage;
    }
}
