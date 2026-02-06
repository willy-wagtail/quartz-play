package com.willy.quartzplay.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExampleJobService {

    private static final Logger log = LoggerFactory.getLogger(ExampleJobService.class);

    public void run() {
        log.info("Example job running...");
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Example job complete.");
    }
}
