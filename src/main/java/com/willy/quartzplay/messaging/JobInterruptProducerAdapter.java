package com.willy.quartzplay.messaging;

import java.util.List;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

public class JobInterruptProducerAdapter {

    private final Kafka kafka;
    private final ObjectMapper objectMapper;
    private final String topicName;

    public static JobInterruptProducerAdapter create(KafkaTemplate<String, String> kafkaTemplate,
                                                      ObjectMapper objectMapper,
                                                      String topicName) {
        return new JobInterruptProducerAdapter(new RealKafka(kafkaTemplate), objectMapper, topicName);
    }

    public static JobInterruptProducerAdapter createNull(ObjectMapper objectMapper,
                                                          List<String> sentMessages) {
        return new JobInterruptProducerAdapter(new NulledKafka(sentMessages), objectMapper, "test-topic");
    }

    private JobInterruptProducerAdapter(Kafka kafka, ObjectMapper objectMapper, String topicName) {
        this.kafka = kafka;
        this.objectMapper = objectMapper;
        this.topicName = topicName;
    }

    public void sendInterrupt(String jobName, String jobGroup, String fireInstanceId) {
        var command = new InterruptJobCommand(jobName, jobGroup, fireInstanceId);
        String json = objectMapper.writeValueAsString(command);
        kafka.send(topicName, jobName, json);
    }

    private interface Kafka {
        void send(String topic, String key, String value);
    }

    private record RealKafka(KafkaTemplate<String, String> kafkaTemplate) implements Kafka {

        @Override
            public void send(String topic, String key, String value) {
                kafkaTemplate.send(topic, key, value);
            }
        }

    private record NulledKafka(List<String> sentMessages) implements Kafka {

        @Override
            public void send(String topic, String key, String value) {
                sentMessages.add(value);
            }
        }
}
