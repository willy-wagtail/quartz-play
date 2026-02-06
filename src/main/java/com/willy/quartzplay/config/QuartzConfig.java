package com.willy.quartzplay.config;

import com.willy.quartzplay.job.ExampleJob;
import com.willy.quartzplay.job.GroupName;
import com.willy.quartzplay.job.JobName;
import com.willy.quartzplay.job.TriggerName;
import org.quartz.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JobCronProperties.class)
public class QuartzConfig {

  private final JobCronProperties cronProperties;

  public QuartzConfig(JobCronProperties cronProperties) {
    this.cronProperties = cronProperties;
  }

  @Bean
  JobDetail exampleJobDetail() {
    return JobBuilder.newJob(ExampleJob.class)
        .withIdentity(JobName.EXAMPLE_JOB.toString(), GroupName.DEFAULT.toString())
        .storeDurably()
        .build();
  }

  @Bean
  Trigger exampleJobTrigger(JobDetail exampleJobDetail) {
    return TriggerBuilder.newTrigger()
        .forJob(exampleJobDetail)
        .withIdentity(TriggerName.EXAMPLE_JOB_TRIGGER.toString(), GroupName.DEFAULT.toString())
        .withSchedule(CronScheduleBuilder.cronSchedule(cronProperties.getExampleJobCron()))
        .build();
  }

}
