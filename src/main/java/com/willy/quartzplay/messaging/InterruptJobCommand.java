package com.willy.quartzplay.messaging;

public record InterruptJobCommand(String jobName, String jobGroup, String fireInstanceId) {}
