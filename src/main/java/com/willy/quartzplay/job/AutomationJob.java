package com.willy.quartzplay.job;

import com.willy.quartzplay.service.AutomationJobService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DisallowConcurrentExecution
public class AutomationJob implements InterruptableJob {

    private static final Logger log = LoggerFactory.getLogger(AutomationJob.class);
    private static final TimingConfiguration TIMING =
        new TimingConfiguration(2, 30, TimeUnit.SECONDS);

    private final AutomationJobService automationJobService;
    private final PerformCheckThenAction performCheckThenAction;
    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    public AutomationJob(AutomationJobService automationJobService,
                         PerformCheckThenAction performCheckThenAction) {
        this.automationJobService = automationJobService;
        this.performCheckThenAction = performCheckThenAction;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Automation job started: {}", context.getJobDetail().getKey());
        try {
            CompletableFuture<Boolean> future = performCheckThenAction.executeOnSuccess(
                () -> automationJobService.isReady(interrupted),
                () -> automationJobService.execute(interrupted),
                TIMING,
                interrupted
            );

            boolean success = future.join();

            if (interrupted.get()) {
                log.info("Automation job interrupted: {}", context.getJobDetail().getKey());
            } else if (success) {
                log.info("Automation job succeeded: {}", context.getJobDetail().getKey());
            } else {
                log.warn("Automation job timed out: {}", context.getJobDetail().getKey());
            }
        } catch (Exception e) {
            throw new JobExecutionException("Automation job failed", e);
        }
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
    }
}
