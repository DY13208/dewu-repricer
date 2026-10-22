package com.dewu.repricer.engine;

public record PricingDecision(Action action, Long targetPrice, String reason) {
    public enum Action { CHANGE, HOLD, SKIP, BLOCK }
    static PricingDecision of(Action a, Long p, String r) { return new PricingDecision(a, p, r); }
}
