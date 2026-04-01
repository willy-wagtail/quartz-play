package com.willy.quartzplay.config;

import com.willy.quartzplay.service.ExternalServiceAdapter;
import com.willy.quartzplay.service.ExternalServiceAdapter.HealthStatus;
import com.willy.quartzplay.service.ExternalServiceAdapter.NulledConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(ExternalServiceProperties.class)
public class ExternalServiceConfig {

    @Bean
    @Profile("!local")
    ExternalServiceAdapter externalServiceAdapter(ExternalServiceProperties properties) {
        RestClient restClient = RestClient.builder()
            .baseUrl(properties.getBaseUrl())
            .requestFactory(properties.createRequestFactory())
            .build();
        return ExternalServiceAdapter.create(restClient);
    }

    @Bean
    @Profile("local")
    ExternalServiceAdapter nulledExternalServiceAdapter() {
        return ExternalServiceAdapter.createNull(NulledConfig.builder()
            .healthStatus(new HealthStatus(true, "local stub: all systems operational"))
            .build());
    }
}
