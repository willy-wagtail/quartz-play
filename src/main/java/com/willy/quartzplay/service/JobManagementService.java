package com.willy.quartzplay.service;

import com.willy.quartzplay.controller.JobDetailResponse;
import com.willy.quartzplay.listener.TriggerOriginJobListener;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.quartz.*;
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

  public JobManagementService(Scheduler scheduler) {
    this.scheduler = scheduler;
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
}
