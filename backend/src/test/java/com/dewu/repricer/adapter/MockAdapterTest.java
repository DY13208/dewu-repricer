package com.dewu.repricer.adapter;

import com.dewu.repricer.adapter.PlatformAdapter.*;
import com.dewu.repricer.adapter.dewu.*;
import com.dewu.repricer.adapter.mock.MockPlatformAdapter;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MockAdapterTest {
    final ShopContext ctx = new ShopContext(1, "s1", "主体A", "t");
    @Test void pagingAndIdempotentUpdate() {
        var m = new MockPlatformAdapter();
        assertThat(m.listSkuListings(ctx, 1, 30).items()).hasSize(30);
        assertThat(m.listSkuListings(ctx, 1, 30).total()).isEqualTo(100);
        var r1 = m.updateListingPrice(ctx, "L-SKU1-1", 88800, "k1");
        var r2 = m.updateListingPrice(ctx, "L-SKU1-1", 77700, "k1");
        assertThat(r2.requestId()).isEqualTo(r1.requestId());
        assertThat(m.getCurrentPrice(ctx, "L-SKU1-1")).contains(88800L);
        assertThat(m.getUpdateResult(ctx, r1.requestId()).effectivePrice()).isEqualTo(88800);
    }
    @Test void realAdapterWithoutPacksOrCredentialsIsInert() {
        var p = new DewuOpenProperties(null, null, null, null, null, null, null, false, 0, 0, null, null, null, 0, 0, 0);
        var a = new DewuOpenPlatformAdapter(p, new com.dewu.repricer.adapter.dewu.DewuOpenAdapterContractTest.MemStore(), new DewuHttpClient(p, new InMemoryQuotaTracker(), System::currentTimeMillis), java.time.Clock.systemUTC());
        assertThat(a.capabilities().updatePrice()).isFalse();
        assertThat(a.updateListingPrice(ctx, "x", 19900, "k").errorCode()).isEqualTo("NO_PERMISSION");
        assertThatThrownBy(() -> a.getCurrentPrice(ctx, "x")).isInstanceOf(AdapterException.class)
                .satisfies(e -> assertThat(((AdapterException) e).retryable()).isFalse());
        assertThat(a.getCompetitorPrice(ctx, "x")).isEmpty();
    }
}
