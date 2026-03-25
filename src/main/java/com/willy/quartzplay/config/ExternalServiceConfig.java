package com.willy.quartzplay.config;

import com.willy.quartzplay.service.ExternalServiceAdapter;
import com.willy.quartzplay.service.ExternalServiceAdapter.HealthStatus;
import com.willy.quartzplay.service.ExternalServiceAdapter.NulledConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.client.RestClient;

@Configuration
public class ExternalServiceConfig {

  @Bean
  @Profile("!local")
  ExternalServiceAdapter externalServiceAdapter() {
    RestClient restClient = RestClient.builder()
        .baseUrl("https://api.example.com")
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
