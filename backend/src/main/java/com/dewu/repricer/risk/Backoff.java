package com.dewu.repricer.risk;

import java.util.concurrent.ThreadLocalRandom;

/** 指数退避 + 抖动。 */
public final class Backoff {
    private Backoff() {}
    public static long delayMs(int attempt, long baseMs, long maxMs, boolean jitter) {
        long d = (long) Math.min(maxMs, baseMs * Math.pow(2, Math.max(0, attempt - 1)));
        return jitter ? d / 2 + ThreadLocalRandom.current().nextLong(d / 2 + 1) : d;
    }
}
