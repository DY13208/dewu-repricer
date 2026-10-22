package com.dewu.repricer.task;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.Optional;
import java.util.Set;

/** Redis ZSET 实现：优先级+延迟执行，ZREM 原子出队保证多实例下不重复消费同一项。 */
public class RedisTaskQueue implements TaskQueue {
    private static final String KEY = "repricer:queue:items";
    private static final String META = "repricer:queue:ready-at";
    private final StringRedisTemplate redis;
    public RedisTaskQueue(StringRedisTemplate redis) { this.redis = redis; }

    @Override public void push(long id, int priority, long nb) {
        redis.opsForZSet().add(KEY, Long.toString(id), TaskQueue.score(priority, 0));
        redis.opsForHash().put(META, Long.toString(id), Long.toString(nb));
    }
    @Override public Optional<Long> pollReady(long now) {
        Set<ZSetOperations.TypedTuple<String>> head = redis.opsForZSet().rangeWithScores(KEY, 0, 199);
        if (head == null) return Optional.empty();
        for (var t : head) {
            Object nb = redis.opsForHash().get(META, t.getValue());
            if (nb != null && Long.parseLong(nb.toString()) > now) continue;
            Long removed = redis.opsForZSet().remove(KEY, t.getValue());
            if (removed != null && removed > 0) { redis.opsForHash().delete(META, t.getValue()); return Optional.of(Long.parseLong(t.getValue())); }
        }
        return Optional.empty();
    }
    @Override public long size() { Long n = redis.opsForZSet().zCard(KEY); return n == null ? 0 : n; }
    @Override public void clear() { redis.delete(KEY); redis.delete(META); }
}
