package com.willy.quartzplay.job;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public interface PerformCheckThenAction {

    CompletableFuture<Boolean> executeOnSuccess(
        Supplier<Boolean> check,
        Runnable action,
        TimingConfiguration timing,
        AtomicBoolean interrupted
    );
}
