package com.dewu.repricer.engine;

/** 平台价格格式规则下的取整。 */
public final class PriceRules {
    private PriceRules() {}
    public static boolean valid(Strategy.PriceRule r, long p) { return r != Strategy.PriceRule.DEWU_900 || (p > 0 && p % 1000 == 900); }
    /** 不大于 p 的最大合法价格。 */
    public static long floor(Strategy.PriceRule r, long p, long step) {
        if (r == Strategy.PriceRule.DEWU_900) { long k = Math.floorDiv(p - 900, 1000); return k * 1000 + 900; }
        return FeeCalculator.floorStep(p, step);
    }
    /** 不小于 p 的最小合法价格。 */
    public static long ceil(Strategy.PriceRule r, long p, long step) {
        if (r == Strategy.PriceRule.DEWU_900) { long f = floor(r, p, step); return f >= p ? f : f + 1000; }
        return FeeCalculator.ceilStep(p, step);
    }
}
