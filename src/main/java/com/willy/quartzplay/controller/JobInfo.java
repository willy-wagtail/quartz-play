package com.willy.quartzplay.controller;

public sealed interface JobInfo permits JobSummary, MisconfiguredJob {
    String jobName();
    JobState state();
}
