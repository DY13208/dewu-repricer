package com.dewu.repricer.adapter.dewu;

import com.dewu.repricer.adapter.AdapterException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

/**
 * 得物开放平台 HTTP 客户端：生产/沙箱网关切换、公共参数与签名、本地配额、错误码映射。
 * GET：全部参数放 query；POST：全部参数（含公共参数与 sign）放 JSON body —— 参数位置以控制台文档为准，沙箱联调时核实。
 */
public class DewuHttpClient {
    private final DewuOpenProperties props; private final QuotaTracker quota; private final LongSupplier clock;
    private final HttpClient http; private final ObjectMapper om = new ObjectMapper();

    public DewuHttpClient(DewuOpenProperties props, QuotaTracker quota, LongSupplier clock) {
        this.props = props; this.quota = quota; this.clock = clock;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(props.connectTimeoutMs())).build();
    }

    /** 业务错误（非限流/非 token 过期）。 */
    public static class DewuApiException extends AdapterException {
        public final int apiCode;
        public DewuApiException(int apiCode, String msg, boolean retryable, String code) { super(code, msg, retryable); this.apiCode = apiCode; }
    }

    public JsonNode call(long shopId, String method, String path, Map<String, Object> biz, String appKey, String appSecret, String accessToken) {
        if (!quota.tryAcquire(shopId, path, props.rateLimitPerMinute(), props.rateLimitPerDay()))
            throw new AdapterException("LOCAL_QUOTA", "本地配额保护：" + path + " 已达每分钟/每日上限", true);
        Map<String, Object> p = new TreeMap<>();
        biz.forEach((k, v) -> { if (v != null) p.put(k, v); });
        p.put("app_key", appKey);
        p.put("timestamp", clock.getAsLong());
        if (accessToken != null) p.put("access_token", accessToken);
        p.put("sign", DewuSigner.sign(p, appSecret));
        String url = props.gateway() + path;
        HttpRequest.Builder rb = HttpRequest.newBuilder().timeout(Duration.ofMillis(props.readTimeoutMs()))
                .header("Content-Type", "application/json;charset=UTF-8");
        try {
            if ("GET".equals(method)) {
                StringJoiner q = new StringJoiner("&");
                p.forEach((k, v) -> q.add(enc(k) + "=" + enc(DewuSigner.serialize(v))));
                rb.uri(URI.create(url + "?" + q)).GET();
            } else {
                rb.uri(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(p), StandardCharsets.UTF_8));
            }
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return parse(resp.statusCode(), resp.body());
        } catch (AdapterException e) { throw e; }
        catch (java.io.IOException e) { throw new AdapterException("NETWORK", "网络错误: " + e.getMessage(), true); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AdapterException("INTERRUPTED", "请求被中断", true); }
    }

    JsonNode parse(int status, String body) {
        if (status == 429 || status >= 500) throw new AdapterException("HTTP_" + status, "网关错误 " + status, true);
        JsonNode n;
        try { n = om.readTree(body); } catch (Exception e) { throw new AdapterException("BAD_RESPONSE", "响应不是 JSON（HTTP " + status + "）", status >= 500); }
        int code = n.path("code").asInt(status);
        String msg = n.path("msg").asText(n.path("message").asText(""));
        if (props.rateLimitCodes().contains(code)) throw new AdapterException("RATE_LIMITED", "平台限流(" + code + "): " + msg, true);
        if (props.tokenExpiredCodes().contains(code) || status == 401) throw new AdapterException("TOKEN_EXPIRED", "access_token 失效(" + code + "): " + msg, true);
        if (!props.successCodes().contains(code) || status >= 400) throw new DewuApiException(code, "平台返回错误(" + code + "): " + msg, false, "API_" + code);
        return n.path("data");
    }

    /** OAuth 调用（open.dewu.com passport），不走签名。 */
    public JsonNode oauth(String path, Map<String, Object> body) {
        try {
            HttpRequest r = HttpRequest.newBuilder(URI.create(props.oauthBaseUrl() + path)).timeout(Duration.ofMillis(props.readTimeoutMs()))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            HttpResponse<String> resp = http.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode n = parse(resp.statusCode(), resp.body());
            return n.isMissingNode() || n.isNull() ? om.readTree(resp.body()) : n;
        } catch (AdapterException e) { throw e; }
        catch (java.io.IOException e) { throw new AdapterException("NETWORK", "OAuth 网络错误: " + e.getMessage(), true); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AdapterException("INTERRUPTED", "中断", true); }
    }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
