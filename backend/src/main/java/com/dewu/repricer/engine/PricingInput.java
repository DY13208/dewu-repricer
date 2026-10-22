package com.dewu.repricer.engine;

/**
 * @param competitorIsOwnEntity 当前最低价是否来自同一经营主体的店铺（含本店）
 * @param skuMinProfit SKU 级利润底线，优先于策略
 */
public record PricingInput(Long currentPrice, Long competitorPrice, boolean competitorIsOwnEntity,
                           Long cost, Long skuMinProfit, boolean excluded) {}
