package com.willy.quartzplay.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AutomationJobService {

    private static final Logger log = LoggerFactory.getLogger(AutomationJobService.class);
    private static final int CHECKS_BEFORE_READY = 3;

    private final AtomicInteger checkCount = new AtomicInteger(0);

    public boolean isReady(AtomicBoolean interrupted) {
        if (interrupted.get()) {
            return false;
        }
        int count = checkCount.incrementAndGet();
        return count >= CHECKS_BEFORE_READY;
    }

    public List<String> getSecurityIds(AtomicBoolean interrupted) {
        if (interrupted.get()) {
            return List.of();
        }
        log.info("Calculating security IDs...");
        // Example: fetch all securities and filter to eligible ones
        List<String> allSecurities = List.of("SEC-001", "SEC-002", "BOND-003", "SEC-004");
        List<String> filtered = allSecurities.stream()
            .filter(id -> id.startsWith("SEC-"))
            .toList();
        log.info("Found {} eligible securities: {}", filtered.size(), filtered);
        return filtered;
    }

    public void execute(List<String> securityIds, AtomicBoolean interrupted) {
        if (interrupted.get()) {
            log.info("Automation action skipped — interrupted before execution");
            return;
        }
        log.info("Automation action executing on {} securities...", securityIds.size());
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Automation action complete on: {}", securityIds);
        checkCount.set(0);
    }
}
