package com.willy.quartzplay.job;

import com.willy.quartzplay.service.ExampleJobService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.UnableToInterruptJobException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DisallowConcurrentExecution
public class ExampleJob implements InterruptableJob {

    private static final Logger log = LoggerFactory.getLogger(ExampleJob.class);

    private final ExampleJobService exampleJobService;
    private volatile Thread executingThread;

    public ExampleJob(ExampleJobService exampleJobService) {
        this.exampleJobService = exampleJobService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        executingThread = Thread.currentThread();
        try {
            exampleJobService.run();
        } catch (InterruptedException e) {
            log.info("Job interrupted: {}", context.getJobDetail().getKey());
        } catch (Exception e) {
            throw new JobExecutionException("Example job failed", e);
        } finally {
            Thread.interrupted();
            executingThread = null;
        }
    }

    @Override
    public void interrupt() throws UnableToInterruptJobException {
        Thread thread = executingThread;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
