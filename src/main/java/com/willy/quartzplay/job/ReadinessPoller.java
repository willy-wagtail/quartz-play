package com.willy.quartzplay.job;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public interface ReadinessPoller {

    CompletableFuture<Boolean> pollUntilReady(
        Supplier<Boolean> check,
        TimingConfiguration timing,
        AtomicBoolean interrupted
    );
}
