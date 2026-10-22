package com.dewu.repricer.risk;

import com.dewu.repricer.task.InMemoryTaskQueue;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RiskTest {
    @Test void backoffGrowsAndCaps() {
        assertThat(Backoff.delayMs(1, 1000, 60000, false)).isEqualTo(1000);
        assertThat(Backoff.delayMs(3, 1000, 60000, false)).isEqualTo(4000);
        assertThat(Backoff.delayMs(20, 1000, 60000, false)).isEqualTo(60000);
        long j = Backoff.delayMs(3, 1000, 60000, true); assertThat(j).isBetween(2000L, 4000L);
    }
    @Test void rateLimiterBlocksBurst() {
        var l = new TokenBucketRateLimiter(); l.configure(1, 2);
        assertThat(l.tryAcquire(1)).isZero(); assertThat(l.tryAcquire(1)).isZero();
        assertThat(l.tryAcquire(1)).isPositive();
        assertThat(l.tryAcquire(2)).isZero(); // 店铺间隔离
    }
    @Test void breakerOpensAfterThreshold() {
        var b = new ShopCircuitBreaker(3, 60000);
        b.failure(1); b.failure(1); assertThat(b.allow(1)).isTrue();
        b.failure(1); assertThat(b.allow(1)).isFalse(); assertThat(b.allow(2)).isTrue();
    }
    @Test void queuePriorityAndDelay() {
        var q = new InMemoryTaskQueue(); long now = 1000;
        q.push(1, 1, now); q.push(2, 9, now); q.push(3, 9, now + 5000);
        assertThat(q.pollReady(now)).contains(2L);
        assertThat(q.pollReady(now)).contains(1L);
        assertThat(q.pollReady(now)).isEmpty();
        assertThat(q.pollReady(now + 6000)).contains(3L);
    }
}
