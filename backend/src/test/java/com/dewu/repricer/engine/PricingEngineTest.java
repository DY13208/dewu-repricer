package com.dewu.repricer.engine;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static com.dewu.repricer.engine.PricingDecision.Action.*;
import static org.assertj.core.api.Assertions.assertThat;

class PricingEngineTest {
    final PricingEngine engine = new PricingEngine();

    static Strategy s(Strategy.Mode m, Strategy.OffsetType t, String off, Long minProfit, Long minP, Long maxP) {
        return new Strategy(1, m, t, new BigDecimal(off), minProfit, minP, maxP,
                new BigDecimal("0.05"), 1500, 24900L, 3300, new BigDecimal("0.15"), 100);
    }
    static PricingInput in(long cur, Long comp, Long cost) { return new PricingInput(cur, comp, false, cost, null, false); }

    @Test void belowByAmount() {
        var d = engine.decide(s(Strategy.Mode.BELOW, Strategy.OffsetType.AMOUNT, "100", null, null, null), in(100000, 99000L, null));
        assertThat(d.action()).isEqualTo(CHANGE); assertThat(d.targetPrice()).isEqualTo(98900);
    }
    @Test void matchPrice() {
        assertThat(engine.decide(s(Strategy.Mode.MATCH, Strategy.OffsetType.AMOUNT, "0", null, null, null), in(100000, 99000L, null)).targetPrice()).isEqualTo(99000);
    }
    @Test void aboveByPercentRoundsUpToStep() {
        var d = engine.decide(s(Strategy.Mode.ABOVE, Strategy.OffsetType.PERCENT, "1", null, null, null), in(100000, 99050L, null));
        assertThat(d.targetPrice()).isEqualTo(100100); // 99050*1.01=100040.5 -> 100100
    }
    @Test void belowByPercentRoundsDown() {
        var d = engine.decide(s(Strategy.Mode.BELOW, Strategy.OffsetType.PERCENT, "2", null, null, null), in(100000, 100000L, null));
        assertThat(d.targetPrice()).isEqualTo(98000);
    }
    @Test void profitFloorApplies() {
        // 成本 90000，利润底线 2000：所需售价满足 p - max(1500,min(5%p,24900)) - 3300 - 90000 >= 2000
        var st = s(Strategy.Mode.BELOW, Strategy.OffsetType.AMOUNT, "100", 2000L, null, null);
        var d = engine.decide(st, in(100500, 99000L, 90000L));
        long expectedFloor = FeeCalculator.minPriceForProfit(st, 90000, 2000);
        assertThat(d.targetPrice()).isEqualTo(expectedFloor);
        assertThat(FeeCalculator.profit(st, d.targetPrice(), 90000)).isGreaterThanOrEqualTo(2000);
        assertThat(FeeCalculator.profit(st, d.targetPrice() - 100, 90000)).isLessThan(2000);
        assertThat(d.reason()).contains("底线");
    }
    @Test void skuMinProfitOverridesStrategy() {
        var st = s(Strategy.Mode.BELOW, Strategy.OffsetType.AMOUNT, "100", 0L, null, null);
        var d = engine.decide(st, new PricingInput(100000L, 99000L, false, 90000L, 5000L, false));
        assertThat(FeeCalculator.profit(st, d.targetPrice(), 90000)).isGreaterThanOrEqualTo(5000);
    }
    @Test void minAndMaxPrice() {
        assertThat(engine.decide(s(Strategy.Mode.BELOW, Strategy.OffsetType.AMOUNT, "100", null, 99500L, null), in(100000, 99000L, null)).targetPrice()).isEqualTo(99500);
        assertThat(engine.decide(s(Strategy.Mode.ABOVE, Strategy.OffsetType.AMOUNT, "5000", null, null, 102000L), in(100000, 99000L, null)).targetPrice()).isEqualTo(102000);
    }
    @Test void conflictBlocks() {
        var d = engine.decide(s(Strategy.Mode.BELOW, Strategy.OffsetType.AMOUNT, "100", 50000L, null, 100000L), in(100000, 99000L, 90000L));
        assertThat(d.action()).isEqualTo(BLOCK);
    }
    @Test void missingCostBlocksWhenProfitRequired() {
        assertThat(engine.decide(s(Strategy.Mode.BELOW, Strategy.OffsetType.AMOUNT, "100", 1000L, null, null), in(100000, 99000L, null)).action()).isEqualTo(BLOCK);
    }
    @Test void ownEntityHolds() {
        var d = engine.decide(s(Strategy.Mode.BELOW, Strategy.OffsetType.AMOUNT, "100", null, null, null), new PricingInput(100000L, 99000L, true, null, null, false));
        assertThat(d.action()).isEqualTo(HOLD);
    }
    @Test void excludedSkips() {
        assertThat(engine.decide(s(Strategy.Mode.MATCH, Strategy.OffsetType.AMOUNT, "0", null, null, null), new PricingInput(1L, 2L, false, null, null, true)).action()).isEqualTo(SKIP);
    }
    @Test void noCompetitorSkips() {
        assertThat(engine.decide(s(Strategy.Mode.MATCH, Strategy.OffsetType.AMOUNT, "0", null, null, null), in(100000, null, null)).action()).isEqualTo(SKIP);
    }
    @Test void sameTargetHolds() {
        assertThat(engine.decide(s(Strategy.Mode.MATCH, Strategy.OffsetType.AMOUNT, "0", null, null, null), in(99000, 99000L, null)).action()).isEqualTo(HOLD);
    }
    @Test void largeChangeTripsBreaker() {
        var d = engine.decide(s(Strategy.Mode.MATCH, Strategy.OffsetType.AMOUNT, "0", null, null, null), in(100000, 50000L, null));
        assertThat(d.action()).isEqualTo(BLOCK); assertThat(d.reason()).contains("熔断");
    }
    @Test void feeClampedByMinAndMax() {
        var st = s(Strategy.Mode.MATCH, Strategy.OffsetType.AMOUNT, "0", null, null, null);
        assertThat(FeeCalculator.platformFee(st, 10000)).isEqualTo(1500);
        assertThat(FeeCalculator.platformFee(st, 1_000_000)).isEqualTo(24900);
        assertThat(FeeCalculator.platformFee(st, 100000)).isEqualTo(5000);
    }
}
