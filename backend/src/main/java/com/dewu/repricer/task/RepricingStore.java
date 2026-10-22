package com.dewu.repricer.task;

import com.dewu.repricer.engine.Strategy;
import java.util.Optional;

/** 执行器所需的持久化操作，便于用内存实现进行单测。 */
public interface RepricingStore {
    record ItemContext(long itemId, long taskId, boolean dryRun, int priority, int attempts, String idempotencyKey, String status,
                       long shopId, String shopStatus, boolean shopRepricingEnabled, String adapterType,
                       String externalShopId, String ownerEntity, String accessToken,
                       long listingId, String externalListingId, long skuPk, String skuId,
                       Long currentPrice, boolean excluded, Long skuMinProfit, Long cost, Strategy strategy) {}

    Optional<ItemContext> loadItem(long itemId);
    boolean emergencyStopped();
    boolean globalPaused();
    /** 同一经营主体下（含本店）该 SKU 是否有在售出价价格 <= price */
    boolean ownEntityAtOrBelow(String ownerEntity, long skuPk, long price);
    void saveSnapshot(long shopId, long skuPk, Long lowest, boolean own, String source);
    void markItem(long itemId, String status, Long oldPrice, Long target, Long comp, String reason, int attempts, Long nextRetryAtMs, String requestId, String error);
    void applyPrice(long shopId, long listingId, long itemId, Long oldPrice, long newPrice, Long verified, Long comp, Long strategyId, String reason);
    void flagAnomaly(long listingId, String flag);
    void logApi(Long shopId, String adapter, String op, boolean ok, long latencyMs, String code, String msg);
    void refreshTaskCounters(long taskId);
}
