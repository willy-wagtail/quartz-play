package com.willy.quartzplay.job;

public enum TriggerName {

    EXAMPLE_JOB_TRIGGER("example-job-trigger");

    private final String value;

    TriggerName(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }
}
