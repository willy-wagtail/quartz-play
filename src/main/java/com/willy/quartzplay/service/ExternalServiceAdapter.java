package com.willy.quartzplay.service;

import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

public class ExternalServiceAdapter {

    public record HealthStatus(boolean healthy, String message) {}

    public record NulledConfig(List<HealthStatus> healthStatuses) {

        private static final HealthStatus DEFAULT_HEALTH = new HealthStatus(true, "stubbed");

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private List<HealthStatus> healthStatuses = List.of(DEFAULT_HEALTH);

            public Builder healthStatuses(List<HealthStatus> healthStatuses) {
                this.healthStatuses = healthStatuses;
                return this;
            }

            public Builder healthStatus(HealthStatus healthStatus) {
                this.healthStatuses = List.of(healthStatus);
                return this;
            }

            public NulledConfig build() {
                return new NulledConfig(healthStatuses);
            }
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ExternalServiceAdapter.class);

    private final Client client;

    public static ExternalServiceAdapter create(RestClient restClient) {
        return new ExternalServiceAdapter(new HttpClient(restClient));
    }

    public static ExternalServiceAdapter createNull() {
        return createNull(NulledConfig.builder().build());
    }

    public static ExternalServiceAdapter createNull(NulledConfig config) {
        return new ExternalServiceAdapter(new NulledClient(config));
    }

    private ExternalServiceAdapter(Client client) {
        this.client = client;
    }

    public HealthStatus checkHealth() {
        return client.checkHealth();
    }

    private interface Client {
        HealthStatus checkHealth();
    }

    private record HttpClient(RestClient restClient) implements Client {

        @Override
            public HealthStatus checkHealth() {
                String body = restClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(String.class);
                log.info("External service health check: {}", body);
                return new HealthStatus(true, body);
            }
        }

    private record NulledClient(Iterator<HealthStatus> healthStatuses) implements Client {
            private NulledClient(NulledConfig healthStatuses) {
              this(healthStatuses.healthStatuses().iterator());
            }

            @Override
            public HealthStatus checkHealth() {
                HealthStatus status = healthStatuses.hasNext()
                    ? healthStatuses.next()
                    : NulledConfig.DEFAULT_HEALTH;
                log.info("Stubbed external service health check: {}", status);
                return status;
            }
        }
}
