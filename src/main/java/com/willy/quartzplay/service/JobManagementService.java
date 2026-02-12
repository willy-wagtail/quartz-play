package com.willy.quartzplay.service;

import com.willy.quartzplay.controller.JobDetailResponse;
import com.willy.quartzplay.listener.TriggerOriginJobListener;
import com.willy.quartzplay.messaging.JobInterruptProducer;
import com.willy.quartzplay.repository.FiredTriggerRepository;
import com.willy.quartzplay.repository.SkipNextStorageAdapter;
import com.willy.quartzplay.repository.QrtzFiredTrigger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.quartz.*;
import org.quartz.UnableToInterruptJobException;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ResponseStatus;

@Service
public class JobManagementService {

  private static final Logger log = LoggerFactory.getLogger(JobManagementService.class);
  private final Scheduler scheduler;
  private final FiredTriggerRepository firedTriggerRepository;
  private final JobInterruptProducer jobInterruptProducer;
  private final SkipNextStorageAdapter skipNextStorage;

  public JobManagementService(Scheduler scheduler, FiredTriggerRepository firedTriggerRepository,
                              JobInterruptProducer jobInterruptProducer, SkipNextStorageAdapter skipNextStorage) {
    this.scheduler = scheduler;
    this.firedTriggerRepository = firedTriggerRepository;
    this.jobInterruptProducer = jobInterruptProducer;
    this.skipNextStorage = skipNextStorage;
  }

  public List<JobDetailResponse> listJobs() {
    try {
      List<JobDetailResponse> result = new ArrayList<>();
      Set<JobKey> jobKeys = scheduler.getJobKeys(GroupMatcher.anyGroup());

      Set<JobKey> runningJobKeys = Set.copyOf(
          scheduler.getCurrentlyExecutingJobs().stream()
              .map(ctx -> ctx.getJobDetail().getKey())
              .toList()
      );

      for (JobKey jobKey : jobKeys) {
        JobDetail detail = scheduler.getJobDetail(jobKey);
        List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);

        List<JobDetailResponse.TriggerInfo> triggerInfos = triggers.stream()
            .map(trigger -> {
              try {
                String cronExpr = trigger instanceof CronTrigger cron ? cron.getCronExpression() : null;
                Trigger.TriggerState state = scheduler.getTriggerState(trigger.getKey());
                return new JobDetailResponse.TriggerInfo(
                    trigger.getKey().getName(),
                    state.name(),
                    cronExpr,
                    toInstant(trigger.getNextFireTime()),
                    toInstant(trigger.getPreviousFireTime())
                );
              } catch (SchedulerException e) {
                throw new JobListException(e);
              }
            })
            .toList();

        result.add(new JobDetailResponse(
            jobKey.getName(),
            jobKey.getGroup(),
            detail.getJobClass().getSimpleName(),
            runningJobKeys.contains(jobKey),
            triggerInfos
        ));
      }
      return result;
    } catch (SchedulerException e) {
      throw new JobListException(e);
    }
  }

  private static Instant toInstant(Date date) {
    return date != null ? date.toInstant() : null;
  }

  public void triggerJob(String jobName) {
    try {
      JobKey jobKey = JobKey.jobKey(jobName);

      requireJobExists(jobKey);
      requireJobNotRunning(jobKey);

      JobDataMap data = new JobDataMap();
      data.put(TriggerOriginJobListener.TRIGGER_MANUAL_KEY, true);

      log.info("Triggering job: {}", jobName);
      scheduler.triggerJob(jobKey, data);
    } catch (SchedulerException e) {
      throw new JobTriggerException(jobName, e);
    }
  }

  public void pauseJob(String jobName) {
    try {
      JobKey jobKey = JobKey.jobKey(jobName);
      requireJobExists(jobKey);

      log.info("Pausing job: {}", jobName);
      scheduler.pauseJob(jobKey);
    } catch (SchedulerException e) {
      throw new JobPauseException(jobName, e);
    }
  }

  public void resumeJob(String jobName) {
    try {
      JobKey jobKey = JobKey.jobKey(jobName);
      requireJobExists(jobKey);

      log.info("Resuming job: {}", jobName);
      scheduler.resumeJob(jobKey);
    } catch (SchedulerException e) {
      throw new JobResumeException(jobName, e);
    }
  }

  // Persists a skip flag to the DB. On the next cron firing, SkipNextTriggerListener picks it up
  // and vetoes the execution, then deletes the flag so subsequent firings proceed normally.
  public void skipNextExecution(String jobName) {
    try {
      JobKey jobKey = JobKey.jobKey(jobName);
      requireJobExists(jobKey);

      List<CronTrigger> cronTriggers = scheduler.getTriggersOfJob(jobKey).stream()
          .filter(CronTrigger.class::isInstance)
          .map(CronTrigger.class::cast)
          .toList();

      if (cronTriggers.isEmpty()) {
        throw new NoCronTriggersException(jobName);
      }

      for (CronTrigger ct : cronTriggers) {
        if (scheduler.getTriggerState(ct.getKey()) == Trigger.TriggerState.PAUSED) {
          throw new JobPausedException(jobName);
        }
      }

      if (skipNextStorage.exists(jobName)) {
        log.info("Skip-next already set for job: {}", jobName);
        return;
      }

      skipNextStorage.save(jobName);
      log.info("Skip-next set for job: {}", jobName);
    } catch (SchedulerException e) {
      throw new SkipNextException(jobName, e);
    }
  }

  public void interruptJob(String jobName) {
    try {
      JobKey jobKey = JobKey.jobKey(jobName);
      requireJobExists(jobKey);

      QrtzFiredTrigger trigger = firedTriggerRepository
          .findFirstBySchedNameAndJobNameAndJobGroupAndState(
              scheduler.getSchedulerName(), jobKey.getName(), jobKey.getGroup(), "EXECUTING")
          .orElseThrow(() -> new JobNotRunningException(jobName));

      // Broadcast interrupt via Kafka (all nodes will receive)
      jobInterruptProducer.sendInterrupt(jobName, jobKey.getGroup(), trigger.getEntryId());

      // Optimization: if running on this node, interrupt locally for instant effect
      if (trigger.getInstanceName().equals(scheduler.getSchedulerInstanceId())) {
        interruptLocalExecution(jobKey, trigger.getEntryId());
      }

      log.info("Interrupt sent for job: {} (fireId={})", jobName, trigger.getEntryId());
    } catch (SchedulerException e) {
      throw new JobInterruptException(jobName, e);
    }
  }

  private void interruptLocalExecution(JobKey jobKey, String fireInstanceId) throws SchedulerException {
    for (JobExecutionContext ctx : scheduler.getCurrentlyExecutingJobs()) {
      if (ctx.getJobDetail().getKey().equals(jobKey)
              && ctx.getFireInstanceId().equals(fireInstanceId)) {
        try {
          ((InterruptableJob) ctx.getJobInstance()).interrupt();
        } catch (UnableToInterruptJobException e) {
          log.warn("Local interrupt failed, Kafka message will handle it", e);
        }
        return;
      }
    }
  }

  private boolean isAlreadyRunning(JobKey jobKey) throws SchedulerException {
    return scheduler
        .getCurrentlyExecutingJobs()
        .stream()
        .map(JobExecutionContext::getJobDetail)
        .anyMatch(detail -> detail.getKey().equals(jobKey));
  }

  private void requireJobExists(JobKey jobKey) throws SchedulerException {
    if (!scheduler.checkExists(jobKey)) {
      throw new JobNotFoundException(jobKey.getName());
    }
  }

  private void requireJobNotRunning(JobKey jobKey) throws SchedulerException {
    if (isAlreadyRunning(jobKey)) {
      throw new JobAlreadyRunningException(jobKey.getName());
    }
  }

  @ResponseStatus(HttpStatus.NOT_FOUND)
  public static class JobNotFoundException extends RuntimeException {
    public JobNotFoundException(String jobName) {
      super("Job not found: " + jobName);
    }
  }

  @ResponseStatus(HttpStatus.CONFLICT)
  public static class JobAlreadyRunningException extends RuntimeException {
    public JobAlreadyRunningException(String jobName) {
      super("Job already running: " + jobName);
    }
  }

  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public static class JobListException extends RuntimeException {
    public JobListException(Throwable cause) {
      super("Failed to list jobs", cause);
    }
  }

  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public static class JobTriggerException extends RuntimeException {
    public JobTriggerException(String jobName, Throwable cause) {
      super("Failed to trigger job: " + jobName, cause);
    }
  }

  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public static class JobPauseException extends RuntimeException {
    public JobPauseException(String jobName, Throwable cause) {
      super("Failed to pause job: " + jobName, cause);
    }
  }

  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public static class JobResumeException extends RuntimeException {
    public JobResumeException(String jobName, Throwable cause) {
      super("Failed to resume job: " + jobName, cause);
    }
  }

  @ResponseStatus(HttpStatus.CONFLICT)
  public static class JobNotRunningException extends RuntimeException {
    public JobNotRunningException(String jobName) {
      super("Job not running: " + jobName);
    }
  }

  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public static class JobInterruptException extends RuntimeException {
    public JobInterruptException(String jobName, Throwable cause) {
      super("Failed to interrupt job: " + jobName, cause);
    }
  }

  @ResponseStatus(HttpStatus.CONFLICT)
  public static class JobPausedException extends RuntimeException {
    public JobPausedException(String jobName) {
      super("Job is paused: " + jobName);
    }
  }

  @ResponseStatus(HttpStatus.CONFLICT)
  public static class NoCronTriggersException extends RuntimeException {
    public NoCronTriggersException(String jobName) {
      super("Job has no cron triggers: " + jobName);
    }
  }

  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public static class SkipNextException extends RuntimeException {
    public SkipNextException(String jobName, Throwable cause) {
      super("Failed to set skip-next for job: " + jobName, cause);
    }
  }
}
