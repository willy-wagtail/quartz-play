package com.willy.quartzplay.messaging;

import com.willy.quartzplay.config.InterruptTopicProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(InterruptTopicProperties.class)
public class KafkaTopicConfig {

    @Bean
    public NewTopic jobInterruptsTopic(InterruptTopicProperties props) {
        return new NewTopic(props.getName(), props.getPartitions(), props.getReplicationFactor());
    }
}
