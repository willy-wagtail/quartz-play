package com.willy.quartzplay.messaging;

import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JobInterruptConsumer {

    private static final Logger log = LoggerFactory.getLogger(JobInterruptConsumer.class);

    private final Scheduler scheduler;
    private final ObjectMapper objectMapper;

    public JobInterruptConsumer(Scheduler scheduler, ObjectMapper objectMapper) {
        this.scheduler = scheduler;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "job-interrupts",
                   groupId = "job-interrupt-#{T(java.util.UUID).randomUUID().toString()}")
    public void onInterrupt(String message) {
        InterruptJobCommand command = objectMapper.readValue(message, InterruptJobCommand.class);
        interruptByFireInstanceId(command.jobName(), command.jobGroup(), command.fireInstanceId());
    }

    private void interruptByFireInstanceId(String jobName, String jobGroup, String fireInstanceId) {
        try {
            for (JobExecutionContext ctx : scheduler.getCurrentlyExecutingJobs()) {
                JobKey key = ctx.getJobDetail().getKey();
                if (key.getName().equals(jobName)
                        && key.getGroup().equals(jobGroup)
                        && ctx.getFireInstanceId().equals(fireInstanceId)) {
                    ((InterruptableJob) ctx.getJobInstance()).interrupt();
                    log.info("Interrupted job locally: {} (fireId={})", key, fireInstanceId);
                    return;
                }
            }
        } catch (SchedulerException e) {
            log.warn("Failed to interrupt job locally: {}.{}", jobName, jobGroup, e);
        }
    }
}
