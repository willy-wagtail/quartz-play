package com.willy.quartzplay.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class HealthCheckJobService {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckJobService.class);

    private final ExternalServiceAdapter externalService;

    public HealthCheckJobService(ExternalServiceAdapter externalService) {
        this.externalService = externalService;
    }

    public void run(AtomicBoolean interrupted) {
        if (interrupted.get()) {
            log.info("Health check skipped — interrupted");
            return;
        }

        ExternalServiceAdapter.HealthStatus status = externalService.checkHealth();
        if (status.healthy()) {
            log.info("External service is healthy: {}", status.message());
        } else {
            log.warn("External service is unhealthy: {}", status.message());
        }
    }
}
