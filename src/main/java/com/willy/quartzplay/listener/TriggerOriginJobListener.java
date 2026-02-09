package com.willy.quartzplay.listener;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TriggerOriginJobListener implements JobListener {

    public static final String TRIGGER_MANUAL_KEY = "trigger.manual";

    private static final Logger log = LoggerFactory.getLogger(TriggerOriginJobListener.class);

    @Override
    public String getName() {
        return "TriggerOriginJobListener";
    }

    @Override
    public void jobToBeExecuted(JobExecutionContext context) {
        boolean manual = context.getMergedJobDataMap().getBoolean(TRIGGER_MANUAL_KEY);
        String jobName = context.getJobDetail().getKey().getName();
        log.info("{}: {}", jobName, manual ? "Manually triggered" : "Triggered by scheduler");
    }

    @Override
    public void jobExecutionVetoed(JobExecutionContext context) {
        // no-op
    }

    @Override
    public void jobWasExecuted(JobExecutionContext context, JobExecutionException jobException) {
        // no-op
    }
}
