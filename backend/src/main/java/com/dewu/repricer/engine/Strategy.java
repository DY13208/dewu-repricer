package com.dewu.repricer.engine;

import java.math.BigDecimal;

/** 跟价策略（金额：分）。 */
public record Strategy(long id, Mode mode, OffsetType offsetType, BigDecimal offsetValue,
                       Long minProfit, Long minPrice, Long maxPrice,
                       BigDecimal feeRate, long feeMin, Long feeMax, long fulfillmentFee,
                       BigDecimal maxChangeRatio, long priceStep) {
    public enum Mode { BELOW, MATCH, ABOVE }
    public enum OffsetType { AMOUNT, PERCENT }
}
