package com.willy.quartzplay.job;

public enum JobName {

    EXAMPLE_JOB("example-job"),
    AUTOMATION_JOB("automation-job"),
    HEALTH_CHECK_JOB("health-check-job");

    private final String value;

    JobName(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }
}
