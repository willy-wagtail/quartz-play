package com.willy.quartzplay.service;

import com.willy.quartzplay.job.GroupName;
import com.willy.quartzplay.job.JobName;
import org.quartz.*;
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

  public void triggerJob(JobName jobName) throws SchedulerException {
    JobKey jobKey = toJobKey(jobName);

    requireJobExists(jobKey);
    requireJobNotRunning(jobKey);

    JobDataMap data = new JobDataMap();
    data.put("trigger.manual", true);

    log.info("Triggering job: {}", jobName);
    scheduler.triggerJob(jobKey, data);
  }

  private boolean isAlreadyRunning(JobKey jobKey) throws SchedulerException {
    return scheduler
        .getCurrentlyExecutingJobs()
        .stream()
        .map(JobExecutionContext::getJobDetail)
        .anyMatch(detail -> detail.getKey().equals(jobKey));
  }

  private JobKey toJobKey(JobName jobName) {
    return JobKey.jobKey(jobName.toString(), GroupName.DEFAULT.toString());
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
}
