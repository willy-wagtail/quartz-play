package com.willy.quartzplay.listener;

import com.willy.quartzplay.repository.SkipNextStorageAdapter;
import org.quartz.CronTrigger;
import org.quartz.JobExecutionContext;
import org.quartz.Trigger;
import org.quartz.TriggerListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SkipNextTriggerListener implements TriggerListener {

    private static final Logger log = LoggerFactory.getLogger(SkipNextTriggerListener.class);

    private final SkipNextStorageAdapter skipNextStorage;

    public SkipNextTriggerListener(SkipNextStorageAdapter skipNextStorage) {
        this.skipNextStorage = skipNextStorage;
    }

    @Override
    public String getName() {
        return "SkipNextTriggerListener";
    }

    @Override
    public void triggerFired(Trigger trigger, JobExecutionContext context) {
        // no-op
    }

    @Override
    public boolean vetoJobExecution(Trigger trigger, JobExecutionContext context) {
        if (!(trigger instanceof CronTrigger)) {
            return false;
        }

        String jobName = context.getJobDetail().getKey().getName();
        if (skipNextStorage.exists(jobName)) {
            skipNextStorage.delete(jobName);
            log.info("Vetoing execution of job: {} (skip-next was set)", jobName);
            return true;
        }
        return false;
    }

    @Override
    public void triggerMisfired(Trigger trigger) {
        // no-op
    }

    @Override
    public void triggerComplete(Trigger trigger, JobExecutionContext context,
                                Trigger.CompletedExecutionInstruction triggerInstructionCode) {
        // no-op
    }
}
