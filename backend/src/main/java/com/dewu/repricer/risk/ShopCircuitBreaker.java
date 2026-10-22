package com.dewu.repricer.risk;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 店铺级熔断：连续失败达到阈值后在冷却期内拒绝该店铺调用。 */
@Component
public class ShopCircuitBreaker {
    private final int threshold; private final long coolDownMs;
    private final Map<Long, int[]> failures = new ConcurrentHashMap<>();
    private final Map<Long, Instant> openUntil = new ConcurrentHashMap<>();
    public ShopCircuitBreaker() { this(10, 10 * 60_000); }
    public ShopCircuitBreaker(int threshold, long coolDownMs) { this.threshold = threshold; this.coolDownMs = coolDownMs; }

    public boolean allow(long shopId) {
        Instant u = openUntil.get(shopId);
        if (u == null) return true;
        if (Instant.now().isAfter(u)) { openUntil.remove(shopId); failures.remove(shopId); return true; }
        return false;
    }
    public void success(long shopId) { failures.remove(shopId); }
    public void failure(long shopId) {
        int n = ++failures.computeIfAbsent(shopId, k -> new int[1])[0];
        if (n >= threshold) openUntil.put(shopId, Instant.now().plusMillis(coolDownMs));
    }
    public boolean isOpen(long shopId) { return !allow(shopId); }
}
