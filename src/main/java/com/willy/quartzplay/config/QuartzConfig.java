package com.willy.quartzplay.config;

import com.willy.quartzplay.job.ExampleJob;
import com.willy.quartzplay.listener.SkipNextTriggerListener;
import com.willy.quartzplay.listener.TriggerOriginJobListener;
import com.willy.quartzplay.job.JobName;
import com.willy.quartzplay.repository.SkipNextRepository;
import com.willy.quartzplay.repository.SkipNextStorageAdapter;
import org.quartz.*;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// On every startup, Spring re-registers the job and trigger beans below, overwriting whatever is
// in the DB (spring.quartz.overwrite-existing-jobs=true). This ensures config changes (e.g. cron
// expression updates) take effect on restart, but it also means any runtime modifications to
// triggers (e.g. rescheduleJob) or JobDataMap flags are lost. That's why skip-next state lives in
// a separate job_skip table, not in Quartz's trigger/job metadata.
@Configuration
@EnableConfigurationProperties(JobCronProperties.class)
public class QuartzConfig {

  private final JobCronProperties cronProperties;

  public QuartzConfig(JobCronProperties cronProperties) {
    this.cronProperties = cronProperties;
  }

  @Bean
  SkipNextStorageAdapter skipNextDataAdapter(SkipNextRepository repository) {
    return SkipNextStorageAdapter.create(repository);
  }

  @Bean
  SchedulerFactoryBeanCustomizer listenerCustomizer(SkipNextStorageAdapter skipNextStorage) {
    return schedulerFactoryBean -> {
      schedulerFactoryBean.setGlobalJobListeners(new TriggerOriginJobListener());
      schedulerFactoryBean.setGlobalTriggerListeners(new SkipNextTriggerListener(skipNextStorage));
    };
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
        // DoNothing: skip missed firings (e.g. while paused) and wait for the next cron tick.
        // Without this, the default (FireAndProceed) would immediately fire on resume.
        .withSchedule(CronScheduleBuilder.cronSchedule(cronProperties.getExampleJobCron())
            .withMisfireHandlingInstructionDoNothing())
        .build();
  }

}
