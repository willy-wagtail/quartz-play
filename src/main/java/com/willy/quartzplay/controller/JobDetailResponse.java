package com.willy.quartzplay.controller;

import java.time.Instant;
import java.util.List;

public record JobDetailResponse(
    String jobName,
    String groupName,
    String jobClass,
    boolean currentlyRunning,
    List<TriggerInfo> triggers
) {

    public record TriggerInfo(
        String triggerName,
        String state,
        String cronExpression,
        Instant nextFireTime,
        Instant previousFireTime
    ) {}
}
