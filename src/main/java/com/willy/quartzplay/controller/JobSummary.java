package com.willy.quartzplay.controller;

import java.time.Instant;

public record JobSummary(
    String jobName,
    String state,
    boolean currentlyRunning,
    String cronExpression,
    String timezone,
    Instant nextFireTime,
    Instant previousFireTime
) implements JobInfo {}
