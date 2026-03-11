package com.willy.quartzplay.job;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DefaultPerformCheckThenAction implements PerformCheckThenAction {

    private static final Logger log = LoggerFactory.getLogger(DefaultPerformCheckThenAction.class);

    private static String abbreviate(TimeUnit unit) {
        return switch (unit) {
            case NANOSECONDS -> "ns";
            case MICROSECONDS -> "µs";
            case MILLISECONDS -> "ms";
            case SECONDS -> "s";
            case MINUTES -> "m";
            case HOURS -> "h";
            case DAYS -> "d";
        };
    }

    @Override
    public CompletableFuture<Boolean> executeOnSuccess(
            Supplier<Boolean> check,
            Runnable action,
            TimingConfiguration timing,
            AtomicBoolean interrupted) {

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

        // Timeout: complete with false and shut down after stopAfter
        executor.schedule(() -> {
            if (result.complete(false)) {
                log.info("Check-then-action timed out after {} {}", timing.stopAfter(), timing.unit());
            }
            executor.shutdown();
        }, timing.stopAfter(), timing.unit());

        // Poll: check readiness at fixed delay intervals
        AtomicInteger attemptCount = new AtomicInteger(0);
        String unitAbbrev = abbreviate(timing.unit());

        executor.scheduleWithFixedDelay(() -> {
            if (interrupted.get()) {
                if (result.complete(false)) {
                    log.info("Check-then-action interrupted");
                }
                executor.shutdown();
                return;
            }

            int attempt = attemptCount.incrementAndGet();
            boolean ready = check.get();
            log.info("Readiness check #{} (every {}{}, timeout {}{}): {}",
                    attempt, timing.delay(), unitAbbrev, timing.stopAfter(), unitAbbrev,
                    ready ? "ready" : "not ready");

            if (!ready) {
                return;
            }

            action.run();
            if (result.complete(true)) {
                log.info("Check-then-action completed successfully");
            }
            executor.shutdown();
        }, 0, timing.delay(), timing.unit());

        return result;
    }
}
