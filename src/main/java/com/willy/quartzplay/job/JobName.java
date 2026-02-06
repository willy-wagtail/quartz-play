package com.willy.quartzplay.job;

public enum JobName {

    EXAMPLE_JOB("example-job");

    private final String value;

    JobName(String value) {
        this.value = value;
    }

    public static JobName fromValue(String value) {
        for (JobName name : values()) {
            if (name.value.equals(value)) {
                return name;
            }
        }
        throw new IllegalArgumentException("Unknown job name: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
