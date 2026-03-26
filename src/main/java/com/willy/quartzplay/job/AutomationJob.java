package com.willy.quartzplay.job;

import com.willy.quartzplay.service.AutomationJobService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
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
    private final ReadinessPoller readinessPoller;
    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    public AutomationJob(AutomationJobService automationJobService,
                         ReadinessPoller readinessPoller) {
        this.automationJobService = automationJobService;
        this.readinessPoller = readinessPoller;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Automation job started: {}", context.getJobDetail().getKey());
        try {
            runStep("readiness-check", () -> {
                boolean success = readinessPoller.pollUntilReady(
                    () -> automationJobService.isReady(interrupted),
                    TIMING,
                    interrupted
                ).join();
                if (!success) {
                    throw new RuntimeException("Readiness check timed out or was interrupted");
                }
                return null;
            });

            if (interrupted.get()) return;

            List<String> securityIds = runStep("calculate-securities",
                () -> automationJobService.getSecurityIds(interrupted));

            if (interrupted.get()) return;

            runStep("perform-action", () -> {
                automationJobService.execute(securityIds, interrupted);
                return null;
            });

            log.info("Automation job succeeded: {}", context.getJobDetail().getKey());
        } catch (Exception e) {
            if (interrupted.get()) {
                log.info("Automation job interrupted: {}", context.getJobDetail().getKey());
            } else {
                throw new JobExecutionException("Automation job failed", e);
            }
        }
    }

    private <T> T runStep(String name, Supplier<T> step) {
        log.info("Step [{}] starting", name);
        long startNanos = System.nanoTime();
        T result = step.get();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        log.info("Step [{}] completed in {} ms", name, durationMs);
        return result;
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
    }
}
