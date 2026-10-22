package com.dewu.repricer.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 手续费与净收入计算。费率/上下限/履约费须按商家合同与平台最新规则配置，系统不内置“官方费率”。 */
public final class FeeCalculator {
    private FeeCalculator() {}

    public static long platformFee(Strategy s, long price) {
        long fee = new BigDecimal(price).multiply(s.feeRate()).setScale(0, RoundingMode.HALF_UP).longValue();
        fee = Math.max(fee, s.feeMin());
        if (s.feeMax() != null) fee = Math.min(fee, s.feeMax());
        return fee;
    }
    public static long netIncome(Strategy s, long price) { return price - platformFee(s, price) - s.fulfillmentFee(); }
    public static long profit(Strategy s, long price, long cost) { return netIncome(s, price) - cost; }

    /** 满足利润底线的最低售价（向上取整到步长）。 */
    public static long minPriceForProfit(Strategy s, long cost, long minProfit) {
        long need = cost + minProfit + s.fulfillmentFee();
        BigDecimal denom = BigDecimal.ONE.subtract(s.feeRate());
        if (denom.signum() <= 0) throw new IllegalArgumentException("费率必须小于 100%");
        long p = new BigDecimal(need).divide(denom, 0, RoundingMode.CEILING).longValue();
        p = Math.max(p, need);
        while (profit(s, p, cost) < minProfit) p++;   // 处理费用下限造成的偏差，迭代次数有界
        return ceilStep(p, s.priceStep());
    }
    public static long ceilStep(long p, long step) { return step <= 1 ? p : ((p + step - 1) / step) * step; }
    public static long floorStep(long p, long step) { return step <= 1 ? p : (p / step) * step; }
}
