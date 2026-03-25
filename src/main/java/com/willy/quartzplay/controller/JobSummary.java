package com.willy.quartzplay.controller;

import java.time.Instant;

public record JobSummary(
    String jobName,
    JobState state,
    CronTriggerInfo cronTrigger
) implements JobInfo {

    public enum CronTriggerState {
        ACTIVE,
        PAUSED,
        COMPLETE,
        ERROR
    }

    public record CronTriggerInfo(
        CronTriggerState state,
        String expression,
        String timezone,
        Instant nextFireTime,
        Instant previousFireTime
    ) {}
}
