package com.willy.quartzplay.messaging;

import com.willy.quartzplay.service.JobManagementService;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JobInterruptConsumer {

    private final JobManagementService jobManagementService;
    private final ObjectMapper objectMapper;

    public JobInterruptConsumer(JobManagementService jobManagementService, ObjectMapper objectMapper) {
        this.jobManagementService = jobManagementService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "job-interrupts",
                   groupId = "job-interrupt-#{T(java.util.UUID).randomUUID().toString()}")
    public void onInterrupt(String message) throws SchedulerException {
        InterruptJobCommand command = objectMapper.readValue(message, InterruptJobCommand.class);
        jobManagementService.interruptLocalExecution(
                JobKey.jobKey(command.jobName(), command.jobGroup()),
                command.fireInstanceId());
    }
}
