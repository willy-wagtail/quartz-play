package com.willy.quartzplay.job;

import com.willy.quartzplay.service.ExampleJobService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DisallowConcurrentExecution
public class ExampleJob implements Job {

  private static final Logger log = LoggerFactory.getLogger(ExampleJob.class);

  private final ExampleJobService exampleJobService;

  public ExampleJob(ExampleJobService exampleJobService) {
    this.exampleJobService = exampleJobService;
  }

  @Override
  public void execute(JobExecutionContext context) throws JobExecutionException {
    boolean manual = context
        .getMergedJobDataMap()
        .getBoolean("trigger.manual");

    log.info(manual ? "Manually triggered" : "Triggered by scheduler");

    try {
      exampleJobService.run();
    } catch (Exception e) {
      throw new JobExecutionException("Example job failed", e);
    }
  }
}
