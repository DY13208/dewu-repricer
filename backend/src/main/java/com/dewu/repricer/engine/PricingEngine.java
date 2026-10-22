package com.dewu.repricer.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.dewu.repricer.engine.PricingDecision.Action.*;

/** 纯函数跟价引擎：无 IO、无状态，便于单测与回放。 */
public final class PricingEngine {

    public PricingDecision decide(Strategy s, PricingInput in) {
        if (in.excluded()) return PricingDecision.of(SKIP, null, "SKU 已设置不参与跟价");
        if (in.competitorPrice() == null || in.competitorPrice() <= 0) return PricingDecision.of(SKIP, null, "无可用竞争价");
        if (in.currentPrice() == null) return PricingDecision.of(SKIP, null, "无当前售价");
        if (in.competitorIsOwnEntity())
            return PricingDecision.of(HOLD, in.currentPrice(), "最低价来自同主体店铺，禁止互相压价");

        long comp = in.competitorPrice();
        long offset = s.offsetType() == Strategy.OffsetType.PERCENT
                ? new BigDecimal(comp).multiply(s.offsetValue()).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValue()
                : s.offsetValue().setScale(0, RoundingMode.HALF_UP).longValue();
        long raw = switch (s.mode()) {
            case BELOW -> comp - offset;
            case MATCH -> comp;
            case ABOVE -> comp + offset;
        };
        long target = s.mode() == Strategy.Mode.ABOVE ? FeeCalculator.ceilStep(raw, s.priceStep()) : FeeCalculator.floorStep(raw, s.priceStep());

        Long minProfit = in.skuMinProfit() != null ? in.skuMinProfit() : s.minProfit();
        long floor = s.minPrice() == null ? 1 : s.minPrice();
        if (minProfit != null) {
            if (in.cost() == null) return PricingDecision.of(BLOCK, null, "缺少采购成本，无法校验利润底线");
            floor = Math.max(floor, FeeCalculator.minPriceForProfit(s, in.cost(), minProfit));
        }
        if (s.maxPrice() != null && floor > s.maxPrice())
            return PricingDecision.of(BLOCK, null, "约束冲突：利润/最低价底线 " + floor + " 高于最高售价 " + s.maxPrice());

        String reason = "跟随竞争价 " + comp;
        if (target < floor) { target = floor; reason = "受底线约束，取底价 " + floor; }
        if (s.maxPrice() != null && target > s.maxPrice()) { target = s.maxPrice(); reason = "受最高售价约束"; }

        long cur = in.currentPrice();
        if (target == cur) return PricingDecision.of(HOLD, cur, "价格无需调整");

        BigDecimal ratio = BigDecimal.valueOf(Math.abs(target - cur)).divide(BigDecimal.valueOf(cur), 6, RoundingMode.HALF_UP);
        if (s.maxChangeRatio() != null && ratio.compareTo(s.maxChangeRatio()) > 0)
            return PricingDecision.of(BLOCK, target, "价格变化 " + ratio.movePointRight(2).setScale(2, RoundingMode.HALF_UP) + "% 超过熔断阈值");
        return PricingDecision.of(CHANGE, target, reason);
    }
}
