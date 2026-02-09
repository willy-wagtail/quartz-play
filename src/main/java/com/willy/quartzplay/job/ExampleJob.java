package com.willy.quartzplay.job;

import com.willy.quartzplay.service.ExampleJobService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

@DisallowConcurrentExecution
public class ExampleJob implements Job {

    private final ExampleJobService exampleJobService;

    public ExampleJob(ExampleJobService exampleJobService) {
        this.exampleJobService = exampleJobService;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        try {
            exampleJobService.run();
        } catch (Exception e) {
            throw new JobExecutionException("Example job failed", e);
        }
    }
}
