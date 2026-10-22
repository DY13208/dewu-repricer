package com.dewu.repricer.adapter.dewu;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 单实例内存计数（日窗口按 Asia/Shanghai 自然日）。多实例部署请使用 JdbcQuotaTracker。 */
public class InMemoryQuotaTracker implements QuotaTracker {
    static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private final Map<String, Long> counts = new ConcurrentHashMap<>();
    static String day() { return ZonedDateTime.now(CN).format(DateTimeFormatter.ofPattern("yyyyMMdd")); }
    static String minute() { return ZonedDateTime.now(CN).format(DateTimeFormatter.ofPattern("yyyyMMddHHmm")); }

    @Override public synchronized boolean tryAcquire(long shopId, String api, int perMinute, int perDay) {
        String m = shopId + "|" + api + "|" + minute(), d = shopId + "|" + api + "|" + day();
        if (counts.getOrDefault(m, 0L) >= perMinute || counts.getOrDefault(d, 0L) >= perDay) return false;
        counts.merge(m, 1L, Long::sum); counts.merge(d, 1L, Long::sum);
        return true;
    }
    @Override public long count(long shopId, String api, boolean daily) {
        return counts.getOrDefault(shopId + "|" + api + "|" + (daily ? day() : minute()), 0L);
    }
}
