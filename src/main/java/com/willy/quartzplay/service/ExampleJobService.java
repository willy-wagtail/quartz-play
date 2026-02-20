package com.willy.quartzplay.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ExampleJobService {

    private static final Logger log = LoggerFactory.getLogger(ExampleJobService.class);

    public void run(AtomicBoolean interrupted) throws InterruptedException {
        log.info("Example job running...");
        for (int i = 0; i < 10; i++) {
            if (interrupted.get()) {
                log.info("Example job stopping early after {} seconds.", i);
                return;
            }
            Thread.sleep(1_000);
        }
        log.info("Example job complete.");
    }
}
