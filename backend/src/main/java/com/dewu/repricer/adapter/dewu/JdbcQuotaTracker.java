package com.dewu.repricer.adapter.dewu;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 基于 api_call_counters 表的计数，多实例共享；超限时回滚本次计数。 */
@Component
public class JdbcQuotaTracker implements QuotaTracker {
    private final JdbcTemplate jdbc;
    public JdbcQuotaTracker(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private long incr(long shopId, String api, String bucket, int delta) {
        Long v = jdbc.queryForObject("""
            INSERT INTO api_call_counters(shop_id, api, bucket, calls) VALUES (?,?,?,?)
            ON CONFLICT (shop_id, api, bucket) DO UPDATE SET calls = api_call_counters.calls + EXCLUDED.calls RETURNING calls""",
                Long.class, shopId, api, bucket, delta);
        return v == null ? 0 : v;
    }
    @Override public boolean tryAcquire(long shopId, String api, int perMinute, int perDay) {
        String m = InMemoryQuotaTracker.minute(), d = InMemoryQuotaTracker.day();
        long cm = incr(shopId, api, m, 1), cd = incr(shopId, api, d, 1);
        if (cm > perMinute || cd > perDay) { incr(shopId, api, m, -1); incr(shopId, api, d, -1); return false; }
        return true;
    }
    @Override public long count(long shopId, String api, boolean daily) {
        List<Long> r = jdbc.queryForList("SELECT calls FROM api_call_counters WHERE shop_id=? AND api=? AND bucket=?", Long.class,
                shopId, api, daily ? InMemoryQuotaTracker.day() : InMemoryQuotaTracker.minute());
        return r.isEmpty() ? 0 : r.get(0);
    }
}
