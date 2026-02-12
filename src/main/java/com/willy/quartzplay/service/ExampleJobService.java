package com.willy.quartzplay.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExampleJobService {

    private static final Logger log = LoggerFactory.getLogger(ExampleJobService.class);

    public void run() throws InterruptedException {
        log.info("Example job running...");
        Thread.sleep(10_000);
        log.info("Example job complete.");
    }
}
