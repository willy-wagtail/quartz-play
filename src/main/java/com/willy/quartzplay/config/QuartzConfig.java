package com.willy.quartzplay.config;

import com.willy.quartzplay.job.ExampleJob;
import com.willy.quartzplay.listener.TriggerOriginJobListener;
import com.willy.quartzplay.job.JobName;
import org.quartz.*;
import org.quartz.impl.matchers.EverythingMatcher;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
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
  SchedulerFactoryBeanCustomizer triggerOriginListenerCustomizer() {
    return schedulerFactoryBean -> schedulerFactoryBean.setGlobalJobListeners(new TriggerOriginJobListener());
  }

  @Bean
  JobDetail exampleJobDetail() {
    return JobBuilder.newJob(ExampleJob.class)
        .withIdentity(JobName.EXAMPLE_JOB.toString())
        .storeDurably()
        .build();
  }

  @Bean
  Trigger exampleJobTrigger(JobDetail exampleJobDetail) {
    return TriggerBuilder.newTrigger()
        .forJob(exampleJobDetail)
        .withIdentity(JobName.EXAMPLE_JOB.toString())
        .withSchedule(CronScheduleBuilder.cronSchedule(cronProperties.getExampleJobCron()))
        .build();
  }

}
