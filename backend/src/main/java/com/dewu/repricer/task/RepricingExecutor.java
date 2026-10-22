package com.dewu.repricer.task;

import com.dewu.repricer.adapter.*;
import com.dewu.repricer.adapter.PlatformAdapter.*;
import com.dewu.repricer.engine.*;
import com.dewu.repricer.risk.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 单个任务项执行：风控检查 → 限流 → 取价 → 引擎决策 → (非 dry-run) 幂等改价 → 回查实际报价 → 记录。
 * 可重试错误按指数退避重新入队；不可重试错误直接失败。
 */
public class RepricingExecutor {
    private static final Logger log = LoggerFactory.getLogger(RepricingExecutor.class);
    private final RepricingStore store; private final AdapterRegistry adapters; private final TaskQueue queue;
    private final TokenBucketRateLimiter limiter; private final ShopCircuitBreaker breaker;
    private final PricingEngine engine = new PricingEngine();
    private final boolean globalDryRun; private final int maxAttempts; private final long baseMs, maxMs;
    private final MeterRegistry metrics;

    public RepricingExecutor(RepricingStore store, AdapterRegistry adapters, TaskQueue queue, TokenBucketRateLimiter limiter,
                             ShopCircuitBreaker breaker, boolean globalDryRun, int maxAttempts, long baseMs, long maxMs, MeterRegistry metrics) {
        this.store = store; this.adapters = adapters; this.queue = queue; this.limiter = limiter; this.breaker = breaker;
        this.globalDryRun = globalDryRun; this.maxAttempts = maxAttempts; this.baseMs = baseMs; this.maxMs = maxMs; this.metrics = metrics;
    }

    public String execute(long itemId) {
        Optional<RepricingStore.ItemContext> o = store.loadItem(itemId);
        if (o.isEmpty()) return "MISSING";
        var c = o.get();
        MDC.put("taskId", String.valueOf(c.taskId()));
        try { return doExecute(c); }
        finally { store.refreshTaskCounters(c.taskId()); MDC.remove("taskId"); }
    }

    private String doExecute(RepricingStore.ItemContext c) {
        if (!"PENDING".equals(c.status()) && !"RETRY".equals(c.status())) return c.status(); // 幂等：已终态不重复执行
        if (store.emergencyStopped()) return mark(c, "SKIPPED", null, null, "紧急停止已开启");
        if (!"ACTIVE".equals(c.shopStatus()) || !c.shopRepricingEnabled()) return mark(c, "SKIPPED", null, null, "店铺已暂停跟价");
        if (c.strategy() == null) return mark(c, "SKIPPED", null, null, "未分配策略");
        if (!breaker.allow(c.shopId())) return retry(c, "店铺熔断冷却中", "BREAKER_OPEN");
        if (limiter.tryAcquire(c.shopId()) > 0) { queue.push(c.itemId(), c.priority(), System.currentTimeMillis() + 500); return "THROTTLED"; }

        PlatformAdapter a = adapters.get(c.adapterType());
        ShopContext sc = new ShopContext(c.shopId(), c.externalShopId(), c.ownerEntity(), c.accessToken());
        try {
            Long current = a.capabilities().currentPrice()
                    ? call(c, a, "getCurrentPrice", () -> a.getCurrentPrice(sc, c.externalListingId())).orElse(c.currentPrice())
                    : c.currentPrice();
            Long comp = null; boolean own = false;
            if (a.capabilities().competitorPrice()) {
                Optional<CompetitorPrice> cp = call(c, a, "getCompetitorPrice", () -> a.getCompetitorPrice(sc, c.skuId()));
                if (cp.isPresent() && cp.get().lowestPrice() != null) {
                    comp = cp.get().lowestPrice();
                    own = c.ownerEntity() != null && store.ownEntityAtOrBelow(c.ownerEntity(), c.skuPk(), comp);
                    store.saveSnapshot(c.shopId(), c.skuPk(), comp, own, cp.get().source());
                }
            }
            PricingDecision d = engine.decide(c.strategy(), new PricingInput(current, comp, own, c.cost(), c.skuMinProfit(), c.excluded()));
            metrics.counter("repricer.decisions", "action", d.action().name()).increment();
            switch (d.action()) {
                case SKIP, HOLD -> { breaker.success(c.shopId()); return mark(c, "SKIPPED", current, d.targetPrice(), comp, d.reason()); }
                case BLOCK -> { store.flagAnomaly(c.listingId(), d.reason()); return mark(c, "BLOCKED", current, d.targetPrice(), comp, d.reason()); }
                default -> {}
            }
            if (globalDryRun || c.dryRun() || store.globalPaused()) {
                return mark(c, "DRY_RUN", current, d.targetPrice(), comp, "[模拟] " + d.reason());
            }
            if (!a.capabilities().updatePrice()) return mark(c, "FAILED", current, d.targetPrice(), comp, "适配器不支持改价");
            if (store.emergencyStopped()) return mark(c, "SKIPPED", current, d.targetPrice(), comp, "紧急停止已开启");

            final Long cur = current, cmp = comp;
            UpdateResult r = call(c, a, "updateListingPrice", () -> a.updateListingPrice(sc, c.externalListingId(), d.targetPrice(), c.idempotencyKey()));
            if (r.status() == UpdateStatus.FAILED) { breaker.failure(c.shopId()); return mark(c, "FAILED", cur, d.targetPrice(), cmp, "平台拒绝: " + r.message()); }
            if (r.status() == UpdateStatus.RETRYABLE) return retry(c, r.message(), r.errorCode());
            // 回查实际报价
            Long verified = r.effectivePrice();
            if (verified == null && r.requestId() != null) {
                UpdateResult q = call(c, a, "getUpdateResult", () -> a.getUpdateResult(sc, r.requestId()));
                verified = q.effectivePrice();
            }
            if (verified == null && a.capabilities().currentPrice())
                verified = call(c, a, "getCurrentPrice", () -> a.getCurrentPrice(sc, c.externalListingId())).orElse(null);
            breaker.success(c.shopId());
            store.applyPrice(c.shopId(), c.listingId(), c.itemId(), cur, d.targetPrice(), verified, cmp, c.strategy().id(), d.reason());
            if (verified == null || verified != d.targetPrice().longValue()) {
                store.flagAnomaly(c.listingId(), "回查价格不一致: 目标 " + d.targetPrice() + " 实际 " + verified);
                store.markItem(c.itemId(), "UNVERIFIED", cur, d.targetPrice(), cmp, "回查不一致", c.attempts() + 1, null, r.requestId(), null);
                return "UNVERIFIED";
            }
            store.markItem(c.itemId(), "VERIFIED", cur, d.targetPrice(), cmp, d.reason(), c.attempts() + 1, null, r.requestId(), null);
            metrics.counter("repricer.price.updated").increment();
            return "VERIFIED";
        } catch (AdapterException e) {
            breaker.failure(c.shopId());
            if (e.retryable()) return retry(c, e.getMessage(), e.code());
            store.markItem(c.itemId(), "FAILED", null, null, null, null, c.attempts() + 1, null, null, e.code() + ": " + e.getMessage());
            return "FAILED";
        } catch (RuntimeException e) {
            log.error("任务项 {} 执行异常", c.itemId(), e);
            return retry(c, e.getMessage(), "INTERNAL");
        }
    }

    private <T> T call(RepricingStore.ItemContext c, PlatformAdapter a, String op, Supplier<T> s) {
        long t0 = System.currentTimeMillis();
        try { T r = s.get(); store.logApi(c.shopId(), a.type(), op, true, System.currentTimeMillis() - t0, null, null); return r; }
        catch (AdapterException e) { store.logApi(c.shopId(), a.type(), op, false, System.currentTimeMillis() - t0, e.code(), e.getMessage()); throw e; }
    }
    private String retry(RepricingStore.ItemContext c, String msg, String code) {
        int n = c.attempts() + 1;
        if (n >= maxAttempts) { store.markItem(c.itemId(), "FAILED", null, null, null, null, n, null, null, "重试耗尽: " + code + " " + msg); return "FAILED"; }
        long next = System.currentTimeMillis() + Backoff.delayMs(n, baseMs, maxMs, true);
        store.markItem(c.itemId(), "RETRY", null, null, null, null, n, next, null, code + ": " + msg);
        queue.push(c.itemId(), c.priority(), next);
        return "RETRY";
    }
    private String mark(RepricingStore.ItemContext c, String st, Long old, Long tgt, String reason) { return mark(c, st, old, tgt, null, reason); }
    private String mark(RepricingStore.ItemContext c, String st, Long old, Long tgt, Long comp, String reason) {
        store.markItem(c.itemId(), st, old, tgt, comp, reason, c.attempts() + 1, null, null, null); return st;
    }
}
