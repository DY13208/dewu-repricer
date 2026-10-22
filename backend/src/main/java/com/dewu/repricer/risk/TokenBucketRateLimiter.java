package com.dewu.repricer.risk;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按店铺（授权维度）的令牌桶限流。单实例内存实现；多实例部署可替换为 Redis Lua 实现。 */
@Component
public class TokenBucketRateLimiter {
    private static final class Bucket { double tokens; long last; double qps; }
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();

    public void configure(long shopId, double qps) {
        buckets.compute(shopId, (k, b) -> { if (b == null) { b = new Bucket(); b.tokens = qps; b.last = System.nanoTime(); } b.qps = qps; return b; });
    }
    /** 非阻塞尝试获取；返回 0 表示成功，否则返回建议等待毫秒数。 */
    public synchronized long tryAcquire(long shopId) {
        Bucket b = buckets.computeIfAbsent(shopId, k -> { Bucket n = new Bucket(); n.qps = 1; n.tokens = 1; n.last = System.nanoTime(); return n; });
        long now = System.nanoTime();
        b.tokens = Math.min(Math.max(1, b.qps), b.tokens + (now - b.last) / 1e9 * b.qps);
        b.last = now;
        if (b.tokens >= 1) { b.tokens -= 1; return 0; }
        return (long) Math.ceil((1 - b.tokens) / b.qps * 1000);
    }
    public void acquire(long shopId) throws InterruptedException {
        long w; while ((w = tryAcquire(shopId)) > 0) Thread.sleep(w);
    }
}
