package com.dewu.repricer.adapter.dewu;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Set;

/**
 * 得物开放平台配置。凭据（ISV client_id/secret）只从环境变量注入；自研应用的 app_key/secret 按店铺加密存库。
 * grantedPacks：已在开放平台申请并审核通过的权限包，决定适配器能力声明。可选值：
 *   BIDDING_READ（出价列表/详情）、BIDDING_WRITE（改价）、AUTO_BIDDING（官方自动跟价）、LOWEST_PRICE（最低价）。
 */
@ConfigurationProperties(prefix = "repricer.dewu-open")
public record DewuOpenProperties(
        String env,                   // PROD / SANDBOX
        String prodGateway, String sandboxGateway, String oauthBaseUrl,
        String isvAppKey, String isvAppSecret,
        Set<String> grantedPacks,
        boolean lowestPriceEnabled,   // 最低价接口参数待控制台核实，默认关闭
        int rateLimitPerMinute, int rateLimitPerDay,
        List<Integer> successCodes, List<Integer> rateLimitCodes, List<Integer> tokenExpiredCodes,
        int connectTimeoutMs, int readTimeoutMs, long tokenRefreshAheadSeconds) {

    public DewuOpenProperties {
        if (env == null || env.isBlank()) env = "SANDBOX";
        if (prodGateway == null || prodGateway.isBlank()) prodGateway = "https://openapi.dewu.com";
        if (sandboxGateway == null || sandboxGateway.isBlank()) sandboxGateway = "https://openapi-sandbox.dewu.com";
        if (oauthBaseUrl == null || oauthBaseUrl.isBlank()) oauthBaseUrl = "https://open.dewu.com";
        if (grantedPacks == null) grantedPacks = Set.of();
        if (rateLimitPerMinute <= 0) rateLimitPerMinute = 1000;
        if (rateLimitPerDay <= 0) rateLimitPerDay = 20000;
        if (successCodes == null || successCodes.isEmpty()) successCodes = List.of(200, 0);
        if (rateLimitCodes == null || rateLimitCodes.isEmpty()) rateLimitCodes = List.of(5030);
        if (tokenExpiredCodes == null) tokenExpiredCodes = List.of();   // 待控制台核实具体错误码
        if (connectTimeoutMs <= 0) connectTimeoutMs = 5000;
        if (readTimeoutMs <= 0) readTimeoutMs = 15000;
        if (tokenRefreshAheadSeconds <= 0) tokenRefreshAheadSeconds = 1800;
    }
    public String gateway() { return "PROD".equalsIgnoreCase(env) ? prodGateway : sandboxGateway; }
    public boolean has(String pack) { return grantedPacks.contains(pack); }
    public boolean configured() { return true; }
}
