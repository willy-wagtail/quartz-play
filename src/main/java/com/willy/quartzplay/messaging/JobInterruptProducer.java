package com.willy.quartzplay.messaging;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class JobInterruptProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public JobInterruptProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void sendInterrupt(String jobName, String jobGroup, String fireInstanceId) {
        var command = new InterruptJobCommand(jobName, jobGroup, fireInstanceId);
        String json = objectMapper.writeValueAsString(command);
        kafkaTemplate.send("job-interrupts", jobName, json);
    }
}
