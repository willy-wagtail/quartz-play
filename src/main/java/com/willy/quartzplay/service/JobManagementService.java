package com.willy.quartzplay.service;

import com.willy.quartzplay.controller.JobDetailResponse;
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

import java.time.Instant;
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

  private static Instant toInstant(Date date) {
    return date != null ? date.toInstant() : null;
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

  public void rescheduleJob(String jobName, String cronExpression) {
    try {
      JobKey jobKey = JobKey.jobKey(jobName);
      requireJobExists(jobKey);

      if (!CronExpression.isValidExpression(cronExpression)) {
        throw new InvalidCronExpressionException(cronExpression);
      }

      requireExactlyOneCronTrigger(jobKey);

      CronTrigger ct = getCronTriggers(jobKey).getFirst();
      boolean wasPaused = scheduler.getTriggerState(ct.getKey()) == Trigger.TriggerState.PAUSED;

      Trigger newTrigger = TriggerBuilder.newTrigger()
          .withIdentity(ct.getKey())
          .forJob(jobKey)
          .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
              .withMisfireHandlingInstructionDoNothing())
          .build();

      scheduler.rescheduleJob(ct.getKey(), newTrigger);

      if (wasPaused) {
        scheduler.pauseTrigger(ct.getKey());
      }

      log.info("Rescheduled job: {} with cron: {}", jobName, cronExpression);
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

      List<CronTrigger> cronTriggers = getCronTriggers(jobKey);

      if (cronTriggers.isEmpty()) {
        throw new NoCronTriggersException(jobName);
      }

      boolean allPaused = cronTriggers.stream()
          .allMatch(ct -> {
            try {
              return scheduler.getTriggerState(ct.getKey()) == Trigger.TriggerState.PAUSED;
            } catch (SchedulerException e) {
              throw new SkipNextException(jobName, e);
            }
          });

      if (allPaused) {
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
      requireJobNotRunningOnAnyNode(jobKey);

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

  private void requireInterruptable(JobKey jobKey) throws SchedulerException {
    Class<? extends Job> jobClass = scheduler.getJobDetail(jobKey).getJobClass();
    if (!InterruptableJob.class.isAssignableFrom(jobClass)) {
      throw new JobNotInterruptableException(jobKey.getName());
    }
  }

  private List<CronTrigger> getCronTriggers(JobKey jobKey) throws SchedulerException {
    return scheduler
        .getTriggersOfJob(jobKey).stream()
        .filter(CronTrigger.class::isInstance)
        .map(CronTrigger.class::cast)
        .toList();
  }

  private void requireExactlyOneCronTrigger(JobKey jobKey) throws SchedulerException {
    long count = scheduler
        .getTriggersOfJob(jobKey).stream()
        .filter(CronTrigger.class::isInstance)
        .count();

    if (count == 0) {
      throw new NoCronTriggersException(jobKey.getName());
    }
    if (count > 1) {
      throw new MultipleCronTriggersException(jobKey.getName());
    }
  }

  private void requireJobNotRunning(JobKey jobKey) throws SchedulerException {
    if (isAlreadyRunning(jobKey)) {
      throw new JobAlreadyRunningException(jobKey.getName());
    }
  }

  // Checks the QRTZ_FIRED_TRIGGERS table, which covers all nodes in a cluster.
  // requireJobNotRunning only checks local execution via getCurrentlyExecutingJobs().
  private void requireJobNotRunningOnAnyNode(JobKey jobKey) throws SchedulerException {
    firedTriggerStorage.findExecutingTrigger(
        scheduler.getSchedulerName(), jobKey.getName(), jobKey.getGroup()
    ).ifPresent(_ -> {
      throw new JobAlreadyRunningException(jobKey.getName());
    });
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
      super("Job has no cron triggers: " + jobName);
    }
  }

  @ResponseStatus(HttpStatus.CONFLICT)
  public static class MultipleCronTriggersException extends RuntimeException {
    public MultipleCronTriggersException(String jobName) {
      super("Job has multiple cron triggers (expected exactly one): " + jobName);
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
}
