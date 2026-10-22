package com.dewu.repricer.adapter.dewu;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 得物开放平台凭据，仅从环境变量/配置注入，禁止写入代码库。 */
@ConfigurationProperties(prefix = "repricer.dewu-open")
public record DewuOpenProperties(String baseUrl, String appKey, String appSecret) {
    public boolean configured() { return baseUrl != null && !baseUrl.isBlank() && appKey != null && !appKey.isBlank(); }
}
