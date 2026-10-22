package com.dewu.repricer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "repricer")
public record RepricerProperties(boolean dryRun, String schedulerCron, int maxAttempts, long backoffBaseMs, long backoffMaxMs) {}
