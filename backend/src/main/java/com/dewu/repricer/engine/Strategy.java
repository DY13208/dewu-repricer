package com.dewu.repricer.engine;

import java.math.BigDecimal;

/** 跟价策略（金额：分）。 */
public record Strategy(long id, Mode mode, OffsetType offsetType, BigDecimal offsetValue,
                       Long minProfit, Long minPrice, Long maxPrice,
                       BigDecimal feeRate, long feeMin, Long feeMax, long fulfillmentFee,
                       BigDecimal maxChangeRatio, long priceStep, PriceRule priceRule) {
    /** 平台价格格式约束。DEWU_900：得物改价接口要求价格（分）以 900 结尾，如 19900 = 199 元。 */
    public enum PriceRule { NONE, DEWU_900 }
    public Strategy(long id, Mode mode, OffsetType offsetType, BigDecimal offsetValue, Long minProfit, Long minPrice, Long maxPrice,
                    BigDecimal feeRate, long feeMin, Long feeMax, long fulfillmentFee, BigDecimal maxChangeRatio, long priceStep) {
        this(id, mode, offsetType, offsetValue, minProfit, minPrice, maxPrice, feeRate, feeMin, feeMax, fulfillmentFee, maxChangeRatio, priceStep, PriceRule.NONE);
    }
    public enum Mode { BELOW, MATCH, ABOVE }
    public enum OffsetType { AMOUNT, PERCENT }
}
