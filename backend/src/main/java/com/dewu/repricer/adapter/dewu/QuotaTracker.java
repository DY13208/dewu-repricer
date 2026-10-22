package com.dewu.repricer.adapter.dewu;

/** 按“店铺 + 接口”统计调用量并做本地配额保护（官方默认：每接口每分钟 1000、每天 2 万）。 */
public interface QuotaTracker {
    /** 计数并检查；超出配额返回 false（不计入）。 */
    boolean tryAcquire(long shopId, String api, int perMinute, int perDay);
    long count(long shopId, String api, boolean daily);
}
