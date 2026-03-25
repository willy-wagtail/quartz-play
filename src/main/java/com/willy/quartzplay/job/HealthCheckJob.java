package com.willy.quartzplay.job;

import com.willy.quartzplay.service.HealthCheckJobService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DisallowConcurrentExecution
public class HealthCheckJob implements InterruptableJob {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckJob.class);

    private final HealthCheckJobService healthCheckJobService;
    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    public HealthCheckJob(HealthCheckJobService healthCheckJobService) {
        this.healthCheckJobService = healthCheckJobService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        log.info("Health check job started: {}", context.getJobDetail().getKey());
        try {
            healthCheckJobService.run(interrupted);
        } catch (Exception e) {
            throw new JobExecutionException("Health check job failed", e);
        }

        if (interrupted.get()) {
            log.info("Health check job interrupted: {}", context.getJobDetail().getKey());
        }
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
    }
}
