package com.dewu.repricer.task;

import com.dewu.repricer.adapter.*;
import com.dewu.repricer.adapter.dewu.*;
import com.dewu.repricer.adapter.mock.MockPlatformAdapter;
import com.dewu.repricer.engine.Strategy;
import com.dewu.repricer.risk.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class RepricingExecutorTest {
    static class FakeStore implements RepricingStore {
        ItemContext ctx; String status; int attempts; boolean estop, paused, ownBelow; Long applied; String anomaly; Long verified;
        public Optional<ItemContext> loadItem(long id) {
            return Optional.of(new ItemContext(ctx.itemId(), 1, ctx.dryRun(), 5, attempts, ctx.idempotencyKey(), status, ctx.shopId(), ctx.shopStatus(),
                    ctx.shopRepricingEnabled(), ctx.adapterType(), null, ctx.ownerEntity(), null, ctx.listingId(), ctx.externalListingId(), 1, ctx.skuId(),
                    ctx.currentPrice(), ctx.excluded(), ctx.skuMinProfit(), ctx.cost(), ctx.strategy()));
        }
        public boolean emergencyStopped() { return estop; }
        public boolean globalPaused() { return paused; }
        public boolean ownEntityAtOrBelow(String o, long s, long p) { return ownBelow; }
        public void saveSnapshot(long a, long b, Long c, boolean d, String e) {}
        public void markItem(long id, String st, Long o, Long t, Long c, String r, int at, Long n, String rid, String err) { status = st; attempts = at; }
        public void applyPrice(long s, long l, long i, Long o, long np, Long v, Long c, Long sid, String r) { applied = np; verified = v; }
        public void flagAnomaly(long l, String f) { anomaly = f; }
        public void logApi(Long s, String a, String op, boolean ok, long ms, String c, String m) {}
        public void refreshTaskCounters(long t) {}
    }
    MockPlatformAdapter mock; FakeStore store; InMemoryTaskQueue queue; RepricingExecutor exec; ShopCircuitBreaker breaker;
    static final Strategy BELOW100 = new Strategy(1, Strategy.Mode.BELOW, Strategy.OffsetType.AMOUNT, new BigDecimal("100"), null, null, null,
            BigDecimal.ZERO, 0, null, 0, new BigDecimal("0.15"), 100);

    void setup(boolean globalDry, boolean taskDry, String adapter) {
        mock = new MockPlatformAdapter(); mock.setPrice("L1", 100000); mock.setCompetitor("S1", 99000L);
        var reg = new AdapterRegistry(List.of(mock, new DewuOpenPlatformAdapter(new DewuOpenProperties(null, null, null))));
        store = new FakeStore(); store.status = "PENDING";
        store.ctx = new RepricingStore.ItemContext(10, 1, taskDry, 5, 0, "t1:10", "PENDING", 1, "ACTIVE", true, adapter, null, "主体A", null,
                1, "L1", 1, "S1", 100000L, false, null, null, BELOW100);
        queue = new InMemoryTaskQueue(); breaker = new ShopCircuitBreaker(3, 60000);
        var lim = new TokenBucketRateLimiter(); lim.configure(1, 1000);
        exec = new RepricingExecutor(store, reg, queue, lim, breaker, globalDry, 3, 10, 100, new SimpleMeterRegistry());
    }

    @Test void changesAndVerifies() {
        setup(false, false, "MOCK");
        assertThat(exec.execute(10)).isEqualTo("VERIFIED");
        assertThat(store.applied).isEqualTo(98900); assertThat(store.verified).isEqualTo(98900);
        assertThat(mock.getCurrentPrice(null, "L1")).contains(98900L);
    }
    @Test void idempotentSecondRunDoesNothing() {
        setup(false, false, "MOCK");
        exec.execute(10); store.applied = null;
        assertThat(exec.execute(10)).isEqualTo("VERIFIED"); assertThat(store.applied).isNull();
    }
    @Test void dryRunDoesNotCallUpdate() {
        setup(true, false, "MOCK");
        assertThat(exec.execute(10)).isEqualTo("DRY_RUN");
        assertThat(mock.getCurrentPrice(null, "L1")).contains(100000L);
        setup(false, true, "MOCK");
        assertThat(exec.execute(10)).isEqualTo("DRY_RUN");
    }
    @Test void emergencyStopSkips() {
        setup(false, false, "MOCK"); store.estop = true;
        assertThat(exec.execute(10)).isEqualTo("SKIPPED");
        assertThat(mock.getCurrentPrice(null, "L1")).contains(100000L);
    }
    @Test void ownEntityNoUndercut() {
        setup(false, false, "MOCK"); store.ownBelow = true;
        assertThat(exec.execute(10)).isEqualTo("SKIPPED");
        assertThat(mock.getCurrentPrice(null, "L1")).contains(100000L);
    }
    @Test void bigChangeBlockedAndFlagged() {
        setup(false, false, "MOCK"); mock.setCompetitor("S1", 50000L);
        assertThat(exec.execute(10)).isEqualTo("BLOCKED"); assertThat(store.anomaly).contains("熔断");
    }
    @Test void retryableErrorsBackoffThenFail() {
        setup(false, false, "MOCK"); mock.setFailureRate(1.0);
        assertThat(exec.execute(10)).isEqualTo("RETRY"); assertThat(queue.size()).isEqualTo(1);
        assertThat(exec.execute(10)).isEqualTo("RETRY");
        assertThat(exec.execute(10)).isEqualTo("FAILED");
    }
    @Test void throttledRequeues() {
        setup(false, false, "MOCK");
        var lim = new TokenBucketRateLimiter(); lim.configure(1, 0.001); lim.tryAcquire(1);
        var reg = new AdapterRegistry(List.of(mock));
        var e = new RepricingExecutor(store, reg, queue, lim, breaker, false, 3, 10, 100, new SimpleMeterRegistry());
        assertThat(e.execute(10)).isEqualTo("THROTTLED"); assertThat(queue.size()).isEqualTo(1); assertThat(store.status).isEqualTo("PENDING");
    }
    @Test void realAdapterSkeletonNeverChangesPrice() {
        setup(false, false, "DEWU_OPEN");
        String r = exec.execute(10);
        assertThat(r).isIn("SKIPPED", "FAILED"); assertThat(store.applied).isNull();
    }
}
