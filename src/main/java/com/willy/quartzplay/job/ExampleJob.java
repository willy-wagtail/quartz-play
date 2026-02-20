package com.willy.quartzplay.job;

import com.willy.quartzplay.service.ExampleJobService;
import java.util.concurrent.atomic.AtomicBoolean;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DisallowConcurrentExecution
public class ExampleJob implements InterruptableJob {

    private static final Logger log = LoggerFactory.getLogger(ExampleJob.class);

    private final ExampleJobService exampleJobService;
    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    public ExampleJob(ExampleJobService exampleJobService) {
        this.exampleJobService = exampleJobService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            exampleJobService.run(interrupted);
        } catch (Exception e) {
            throw new JobExecutionException("Example job failed", e);
        }

        if (interrupted.get()) {
            log.info("Job interrupted: {}", context.getJobDetail().getKey());
        }
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
    }
}
