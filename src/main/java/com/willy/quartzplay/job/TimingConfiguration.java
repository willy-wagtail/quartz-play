package com.willy.quartzplay.job;

import java.util.concurrent.TimeUnit;

public record TimingConfiguration(long delay, long stopAfter, TimeUnit unit) {
}
