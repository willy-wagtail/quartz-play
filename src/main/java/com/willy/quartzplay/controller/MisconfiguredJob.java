package com.willy.quartzplay.controller;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MisconfiguredJob(
    String jobName,
    String error,
    boolean currentlyRunning
) implements JobInfo {

    @JsonProperty
    @Override
    public JobState state() {
        return JobState.MISCONFIGURED;
    }
}
