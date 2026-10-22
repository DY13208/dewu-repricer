package com.dewu.repricer.engine;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static com.dewu.repricer.engine.Strategy.PriceRule.DEWU_900;
import static org.assertj.core.api.Assertions.assertThat;

class PriceRulesTest {
    @Test void floorCeil900() {
        assertThat(PriceRules.floor(DEWU_900, 19950, 100)).isEqualTo(19900);
        assertThat(PriceRules.floor(DEWU_900, 19899, 100)).isEqualTo(18900);
        assertThat(PriceRules.ceil(DEWU_900, 19901, 100)).isEqualTo(20900);
        assertThat(PriceRules.ceil(DEWU_900, 19900, 100)).isEqualTo(19900);
        assertThat(PriceRules.valid(DEWU_900, 19900)).isTrue();
        assertThat(PriceRules.valid(DEWU_900, 19800)).isFalse();
    }
    static Strategy s(Strategy.Mode m, String off, Long minProfit, Long maxP) {
        return new Strategy(1, m, Strategy.OffsetType.AMOUNT, new BigDecimal(off), minProfit, null, maxP,
                new BigDecimal("0.05"), 0, null, 0, new BigDecimal("0.5"), 100, DEWU_900);
    }
    @Test void engineOutputsOnly900Endings() {
        var e = new PricingEngine();
        for (long comp = 50000; comp < 60000; comp += 137) {
            for (Strategy.Mode m : Strategy.Mode.values()) {
                var d = e.decide(s(m, "100", 1000L, 80000L), new PricingInput(55900L, comp, false, 40000L, null, false));
                if (d.targetPrice() != null) assertThat(d.targetPrice() % 1000).as("comp=%d mode=%s", comp, m).isEqualTo(900);
            }
        }
    }
    @Test void belowWith900() {
        var d = new PricingEngine().decide(s(Strategy.Mode.BELOW, "100", null, null), new PricingInput(100900L, 99900L, false, null, null, false));
        assertThat(d.targetPrice()).isEqualTo(98900);   // 99800 → 向下取到 98900
    }
    @Test void profitFloorRoundedUpTo900() {
        var st = s(Strategy.Mode.BELOW, "100", 5000L, null);
        var d = new PricingEngine().decide(st, new PricingInput(60900L, 50000L, false, 50000L, null, false));
        assertThat(d.targetPrice() % 1000).isEqualTo(900);
        assertThat(FeeCalculator.profit(st, d.targetPrice(), 50000)).isGreaterThanOrEqualTo(5000);
    }
}
