package com.willy.quartzplay.config;

import com.willy.quartzplay.job.ExampleJob;
import com.willy.quartzplay.listener.SkipNextTriggerListener;
import com.willy.quartzplay.listener.TriggerOriginJobListener;
import com.willy.quartzplay.job.JobName;
import com.willy.quartzplay.messaging.JobInterruptProducerAdapter;
import com.willy.quartzplay.repository.FiredTriggerRepository;
import com.willy.quartzplay.repository.FiredTriggerStorageAdapter;
import com.willy.quartzplay.repository.SkipNextRepository;
import com.willy.quartzplay.repository.SkipNextStorageAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.listeners.SchedulerListenerSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

// These job/trigger beans are seed data for initial deployment only. With
// overwrite-existing-jobs=false, they are stored in the DB on first startup and ignored
// thereafter — the DB is the source of truth. Runtime changes (pause, reschedule) survive
// restarts. To change a cron schedule, use POST /api/jobs/{name}/reschedule.
@Configuration
@EnableConfigurationProperties(JobCronProperties.class)
public class QuartzInitialConfig {

  private static final Logger log = LoggerFactory.getLogger(QuartzInitialConfig.class);

  private final JobCronProperties cronProperties;

  public QuartzInitialConfig(JobCronProperties cronProperties) {
    this.cronProperties = cronProperties;
  }

  @Bean
  SkipNextStorageAdapter skipNextDataAdapter(SkipNextRepository repository) {
    return SkipNextStorageAdapter.create(repository);
  }

  @Bean
  FiredTriggerStorageAdapter firedTriggerStorageAdapter(FiredTriggerRepository repository) {
    return FiredTriggerStorageAdapter.create(repository);
  }

  @Bean
  JobInterruptProducerAdapter jobInterruptProducerAdapter(KafkaTemplate<String, String> kafkaTemplate,
                                                          ObjectMapper objectMapper) {
    return JobInterruptProducerAdapter.create(kafkaTemplate, objectMapper);
  }

  @Bean
  SchedulerFactoryBeanCustomizer listenerCustomizer(SkipNextStorageAdapter skipNextStorage) {
    return schedulerFactoryBean -> {
      schedulerFactoryBean.setGlobalJobListeners(new TriggerOriginJobListener());
      schedulerFactoryBean.setGlobalTriggerListeners(new SkipNextTriggerListener(skipNextStorage));
      schedulerFactoryBean.setSchedulerListeners(new SchedulerListenerSupport() {
        @Override
        public void jobAdded(JobDetail jobDetail) {
          log.info("Seeded new job: {}", jobDetail.getKey().getName());
        }

        @Override
        public void jobScheduled(Trigger trigger) {
          log.info("Seeded new trigger: {} for job: {}", trigger.getKey().getName(),
              trigger.getJobKey().getName());
        }
      });
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

  @Bean
  ApplicationRunner quartzSeedWarningRunner(Scheduler scheduler, List<JobDetail> jobDetails,
                                            List<Trigger> triggers) {
    return _ -> {
      List<String> skipped = new ArrayList<>();
      for (JobDetail job : jobDetails) {
        if (scheduler.checkExists(job.getKey())) {
          skipped.add(job.getKey().getName());
        }
      }
      for (Trigger trigger : triggers) {
        if (scheduler.checkExists(trigger.getKey())) {
          skipped.add("trigger:" + trigger.getKey().getName());
        }
      }
      if (!skipped.isEmpty()) {
        log.warn("Bean definitions skipped (already in DB): {}. "
            + "Use POST /api/jobs/{{name}}/reschedule to change schedules.", skipped);
      }

      // Detect orphaned jobs: present in the scheduler DB but no matching bean definition.
      // These keep firing silently after a job bean is removed from config.
      Set<JobKey> beanJobKeys = jobDetails.stream()
          .map(JobDetail::getKey)
          .collect(Collectors.toSet());

      List<String> orphans = new ArrayList<>();
      for (JobKey key : scheduler.getJobKeys(GroupMatcher.anyGroup())) {
        if (!beanJobKeys.contains(key)) {
          List<? extends Trigger> jobTriggers = scheduler.getTriggersOfJob(key);
          String state = jobTriggers.isEmpty() ? "NO_TRIGGERS"
              : jobTriggers.stream()
                  .map(t -> {
                    try { return scheduler.getTriggerState(t.getKey()).name(); }
                    catch (SchedulerException e) { return "UNKNOWN"; }
                  })
                  .distinct()
                  .sorted()
                  .collect(Collectors.joining("/"));
          orphans.add(key.getName() + " (" + state + ")");
        }
      }
      if (!orphans.isEmpty()) {
        log.warn("Orphaned jobs in DB (no matching bean): {}. Remove with DELETE /api/jobs/{{name}}", orphans);
      }
    };
  }

}
