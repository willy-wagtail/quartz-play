package com.willy.quartzplay.service;

import com.willy.quartzplay.controller.JobInfo;
import com.willy.quartzplay.controller.JobSummary;
import com.willy.quartzplay.controller.MisconfiguredJob;
import com.willy.quartzplay.listener.TriggerOriginJobListener;
import com.willy.quartzplay.messaging.JobInterruptProducerAdapter;
import com.willy.quartzplay.repository.FiredTriggerStorageAdapter;
import com.willy.quartzplay.repository.FiredTriggerStorageAdapter.FiredTriggerInfo;
import com.willy.quartzplay.repository.SkipNextStorageAdapter;
import org.quartz.*;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.*;

@Service
public class JobManagementService {

  private static final Logger log = LoggerFactory.getLogger(JobManagementService.class);

  private final Scheduler scheduler;
  private final FiredTriggerStorageAdapter firedTriggerStorage;
  private final JobInterruptProducerAdapter jobInterruptProducer;
  private final SkipNextStorageAdapter skipNextStorage;

  public JobManagementService(Scheduler scheduler, FiredTriggerStorageAdapter firedTriggerStorage,
                              JobInterruptProducerAdapter jobInterruptProducer, SkipNextStorageAdapter skipNextStorage) {
    this.scheduler = scheduler;
    this.firedTriggerStorage = firedTriggerStorage;
    this.jobInterruptProducer = jobInterruptProducer;
    this.skipNextStorage = skipNextStorage;
  }

  public List<JobInfo> listJobInfo() {
    try {
      List<JobInfo> result = new ArrayList<>();
      for (JobKey jobKey : scheduler.getJobKeys(GroupMatcher.anyGroup())) {
        result.add(getJobInfo(jobKey));
      }
      return result;
    } catch (SchedulerException e) {
      throw new JobListException(e);
    }
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
      logNextFireTime(jobKey);
    } catch (SchedulerException e) {
      throw new JobResumeException(jobName, e);
    }
  }

  public void rescheduleJob(String jobName, String cronExpression, String timezone) {
    try {
      JobKey jobKey = JobKey.jobKey(jobName);
      requireJobExists(jobKey);

      requireValidCronExpression(cronExpression);

      ZoneId zone = requireValidTimezone(timezone);

      CronTrigger ct = requireExactlyOneCronTrigger(jobKey);
      boolean wasPaused = scheduler.getTriggerState(ct.getKey()) == Trigger.TriggerState.PAUSED;

      CronScheduleBuilder schedule = CronScheduleBuilder.cronSchedule(cronExpression)
          .inTimeZone(TimeZone.getTimeZone(zone))
          .withMisfireHandlingInstructionDoNothing();

      Trigger newTrigger = TriggerBuilder.newTrigger()
          .withIdentity(ct.getKey())
          .forJob(jobKey)
          .withSchedule(schedule)
          .build();

      scheduler.rescheduleJob(ct.getKey(), newTrigger);

      if (wasPaused) {
        scheduler.pauseTrigger(ct.getKey());
      }

      log.info("Rescheduled job: {} with cron: {} timezone: {}", jobName, cronExpression, timezone);
      logNextFireTime(jobKey);
    } catch (SchedulerException e) {
      throw new RescheduleException(jobName, e);
    }
  }

  // Persists a skip flag to the DB. On the next cron firing, SkipNextTriggerListener picks it up
  // and vetoes the execution, then deletes the flag so subsequent firings proceed normally.
  public void skipNextExecution(String jobName) {
    try {
      JobKey jobKey = JobKey.jobKey(jobName);
      requireJobExists(jobKey);

      CronTrigger ct = requireExactlyOneCronTrigger(jobKey);

      if (scheduler.getTriggerState(ct.getKey()) == Trigger.TriggerState.PAUSED) {
        throw new JobPausedException(jobName);
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

  public void cancelSkipNext(String jobName) {
    try {
      JobKey jobKey = JobKey.jobKey(jobName);
      requireJobExists(jobKey);

      if (!skipNextStorage.exists(jobName)) {
        log.info("No skip-next flag set for job: {}", jobName);
        return;
      }

      skipNextStorage.delete(jobName);
      log.info("Skip-next cancelled for job: {}", jobName);
    } catch (SchedulerException e) {
      throw new SkipNextException(jobName, e);
    }
  }

  public void deleteJob(String jobName) {
    try {
      JobKey jobKey = JobKey.jobKey(jobName);
      requireJobExists(jobKey);
      requireJobNotRunning(jobKey);

      scheduler.deleteJob(jobKey);
      skipNextStorage.delete(jobName);
      log.info("Deleted job: {}", jobName);
    } catch (SchedulerException e) {
      throw new JobDeleteException(jobName, e);
    }
  }

  public boolean isSkipNextPending(String jobName) {
    try {
      JobKey jobKey = JobKey.jobKey(jobName);
      requireJobExists(jobKey);

      return skipNextStorage.exists(jobName);
    } catch (SchedulerException e) {
      throw new SkipNextException(jobName, e);
    }
  }

  // Interruption is cooperative: the job must implement InterruptableJob and check for a
  // thread-safe flag (e.g. a volatile boolean or AtomicBoolean) in its execution loop.
  // Calling interrupt() simply sets that flag — it does not forcibly stop the thread.
  //
  // Quartz's Scheduler.interrupt() only acts on the local node — in a clustered setup the job
  // may be running on a different instance. We broadcast the interrupt over Kafka so every node
  // receives it, and the one actually executing the job can act on it.
  public void interruptJob(String jobName) {
    try {
      JobKey jobKey = JobKey.jobKey(jobName);
      requireJobExists(jobKey);
      requireInterruptable(jobKey);

      FiredTriggerInfo trigger = firedTriggerStorage
          .findExecutingTrigger(
              scheduler.getSchedulerName(),
              jobKey.getName(),
              jobKey.getGroup()
          )
          .orElseThrow(() -> new JobNotRunningException(jobName));

      // Broadcast interrupt via Kafka (all nodes will receive)
      jobInterruptProducer.sendInterrupt(jobName, jobKey.getGroup(), trigger.fireInstanceId());

      // Optimization: if running on this node, interrupt locally for instant effect
      if (trigger.instanceName().equals(scheduler.getSchedulerInstanceId())) {
        interruptLocalExecution(jobKey, trigger.fireInstanceId());
      }

      log.info("Interrupt sent for job: {} (fireId={})", jobName, trigger.fireInstanceId());
    } catch (SchedulerException e) {
      throw new JobInterruptException(jobName, e);
    }
  }

  public void interruptLocalExecution(JobKey jobKey, String fireInstanceId) throws SchedulerException {
    var ctx = findExecutionContext(jobKey, fireInstanceId);

    if (ctx.isEmpty()) {
      return;
    }

    if (!(ctx.get().getJobInstance() instanceof InterruptableJob interruptable)) {
      log.warn("Job [{}] does not implement InterruptableJob, cannot interrupt", jobKey);
      return;
    }

    try {
      interruptable.interrupt();
      log.info("Interrupted job locally: {} (fireId={})", jobKey, fireInstanceId);
    } catch (UnableToInterruptJobException e) {
      log.warn("Local interrupt failed for job: {} (fireId={})", jobKey, fireInstanceId, e);
    }
  }

  private Optional<JobExecutionContext> findExecutionContext(JobKey jobKey, String fireInstanceId)
      throws SchedulerException {
    return scheduler.getCurrentlyExecutingJobs().stream()
        .filter(ctx -> ctx.getJobDetail().getKey().equals(jobKey)
            && ctx.getFireInstanceId().equals(fireInstanceId))
        .findFirst();
  }

  private void logNextFireTime(JobKey jobKey) throws SchedulerException {
    scheduler
        .getTriggersOfJob(jobKey).stream()
        .filter(t -> t.getNextFireTime() != null)
        .findFirst()
        .ifPresent(t -> log.info("{}: Next fire time: {}", jobKey.getName(), t.getNextFireTime().toInstant()));
  }

  private boolean isJobRunning(JobKey jobKey) throws SchedulerException {
    // Local check (fast path for same-node execution)
    boolean locallyRunning = scheduler.getCurrentlyExecutingJobs().stream()
        .anyMatch(ctx -> ctx.getJobDetail().getKey().equals(jobKey));
    if (locallyRunning) {
      return true;
    }
    // Cluster-wide check via QRTZ_FIRED_TRIGGERS
    return firedTriggerStorage.findExecutingTrigger(
        scheduler.getSchedulerName(), jobKey.getName(), jobKey.getGroup()
    ).isPresent();
  }

  private void requireValidCronExpression(String cronExpression) {
    if (!CronExpression.isValidExpression(cronExpression)) {
      throw new InvalidCronExpressionException(cronExpression);
    }
  }

  private ZoneId requireValidTimezone(String timezone) {
    try {
      return ZoneId.of(timezone);
    } catch (DateTimeException e) {
      throw new InvalidTimezoneException(timezone);
    }
  }

  private void requireJobExists(JobKey jobKey) throws SchedulerException {
    if (!scheduler.checkExists(jobKey)) {
      throw new JobNotFoundException(jobKey.getName());
    }
  }

  private void requireInterruptable(JobKey jobKey) throws SchedulerException {
    Class<? extends Job> jobClass = scheduler.getJobDetail(jobKey).getJobClass();
    if (!InterruptableJob.class.isAssignableFrom(jobClass)) {
      throw new JobNotInterruptableException(jobKey.getName());
    }
  }

  private JobInfo getJobInfo(JobKey jobKey) throws SchedulerException {
    try {
      CronTrigger ct = requireExactlyOneCronTrigger(jobKey);
      return new JobSummary(
          jobKey.getName(),
          scheduler.getTriggerState(ct.getKey()).name(),
          isJobRunning(jobKey),
          ct.getCronExpression(),
          ct.getTimeZone().getID(),
          ct.getNextFireTime() != null ? ct.getNextFireTime().toInstant() : null,
          ct.getPreviousFireTime() != null ? ct.getPreviousFireTime().toInstant() : null
      );
    } catch (NoCronTriggersException | MultipleTriggersException | NotCronTriggerException e) {
      log.warn("Misconfigured job {}: {}", jobKey.getName(), e.getMessage());
      return new MisconfiguredJob(jobKey.getName(), e.getMessage(), isJobRunning(jobKey));
    }
  }

  private CronTrigger requireExactlyOneCronTrigger(JobKey jobKey) throws SchedulerException {
    List<? extends Trigger> triggers = scheduler.getTriggersOfJob(jobKey);
    if (triggers.isEmpty()) {
      throw new NoCronTriggersException(jobKey.getName());
    }
    if (triggers.size() > 1) {
      throw new MultipleTriggersException(jobKey.getName());
    }
    if (!(triggers.getFirst() instanceof CronTrigger ct)) {
      throw new NotCronTriggerException(jobKey.getName());
    }
    return ct;
  }

  private void requireJobNotRunning(JobKey jobKey) throws SchedulerException {
    if (isJobRunning(jobKey)) {
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

  @ResponseStatus(HttpStatus.CONFLICT)
  public static class JobNotInterruptableException extends RuntimeException {
    public JobNotInterruptableException(String jobName) {
      super("Job does not support interruption: " + jobName);
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
      super("Job has no trigger (expected exactly one CronTrigger): " + jobName);
    }
  }

  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public static class SkipNextException extends RuntimeException {
    public SkipNextException(String jobName, Throwable cause) {
      super("Failed to set skip-next for job: " + jobName, cause);
    }
  }

  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public static class InvalidCronExpressionException extends RuntimeException {
    public InvalidCronExpressionException(String cronExpression) {
      super("Invalid cron expression: " + cronExpression);
    }
  }

  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public static class InvalidTimezoneException extends RuntimeException {
    public InvalidTimezoneException(String timezone) {
      super("Invalid timezone: " + timezone);
    }
  }

  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public static class RescheduleException extends RuntimeException {
    public RescheduleException(String jobName, Throwable cause) {
      super("Failed to reschedule job: " + jobName, cause);
    }
  }

  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public static class JobDeleteException extends RuntimeException {
    public JobDeleteException(String jobName, Throwable cause) {
      super("Failed to delete job: " + jobName, cause);
    }
  }

  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public static class MultipleTriggersException extends RuntimeException {
    public MultipleTriggersException(String jobName) {
      super("Job has multiple triggers (expected exactly one CronTrigger): " + jobName);
    }
  }

  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public static class NotCronTriggerException extends RuntimeException {
    public NotCronTriggerException(String jobName) {
      super("Job trigger is not a CronTrigger (expected exactly one CronTrigger): " + jobName);
    }
  }
}
