package com.willy.quartzplay.service;

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
        boolean ready = count >= CHECKS_BEFORE_READY;
        return ready;
    }

    public void execute(AtomicBoolean interrupted) {
        if (interrupted.get()) {
            log.info("Automation action skipped — interrupted before execution");
            return;
        }
        log.info("Automation action executing...");
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Automation action complete.");
        checkCount.set(0);
    }
}
