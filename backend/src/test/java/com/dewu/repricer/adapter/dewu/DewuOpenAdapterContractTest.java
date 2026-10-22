package com.dewu.repricer.adapter.dewu;

import com.dewu.repricer.adapter.AdapterException;
import com.dewu.repricer.adapter.PlatformAdapter.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.*;

/** WireMock 契约测试：模拟官方网关，验证签名、改价、限流、token 过期刷新。响应结构按 docs/07 文档推定。 */
public class DewuOpenAdapterContractTest {
    static WireMockServer wm;
    final ObjectMapper om = new ObjectMapper();
    final ShopContext shop = new ShopContext(1, "s1", "主体A", null);
    final long NOW = 1_700_000_000_000L;
    final Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);

    public static class MemStore implements DewuCredentialStore {
        DewuCredential c; int saves;
        public Optional<DewuCredential> find(long id) { return Optional.ofNullable(c); }
        public void saveTokens(long id, String at, String rt, String oid, Instant e, Instant re) {
            saves++; c = new DewuCredential(id, c.mode(), c.appKey(), c.appSecret(), at, rt == null ? c.refreshToken() : rt, oid, e, re == null ? c.refreshExpiresAt() : re, c.biddingType());
        }
        public void markError(long id, String s, String e) {}
    }
    MemStore store; DewuOpenPlatformAdapter adapter;

    @BeforeAll static void start() { wm = new WireMockServer(options().dynamicPort()); wm.start(); }
    @AfterAll static void stop() { wm.stop(); }

    DewuOpenProperties props(Set<String> packs, List<Integer> tokenCodes, int perMin) {
        return new DewuOpenProperties("SANDBOX", "http://prod.invalid", wm.baseUrl(), wm.baseUrl(), "isvKey", "isvSecret", packs, false,
                perMin, 20000, null, null, tokenCodes, 2000, 5000, 1800);
    }
    void setup(DewuCredential.Mode mode, int biddingType, Set<String> packs, List<Integer> tokenCodes, int perMin) {
        wm.resetAll();
        store = new MemStore();
        store.c = mode == DewuCredential.Mode.SELF_DEVELOPED
                ? new DewuCredential(1, mode, "selfKey", "selfSecret", null, null, null, null, null, biddingType)
                : new DewuCredential(1, mode, null, null, "AT1", "RT1", "oid", Instant.ofEpochMilli(NOW).plusSeconds(86400), Instant.ofEpochMilli(NOW).plusSeconds(86400 * 100), biddingType);
        var p = props(packs, tokenCodes, perMin);
        adapter = new DewuOpenPlatformAdapter(p, store, new DewuHttpClient(p, new InMemoryQuotaTracker(), () -> NOW), clock);
    }
    static final Set<String> ALL = Set.of("BIDDING_READ", "BIDDING_WRITE", "AUTO_BIDDING");
    String ok(String data) { return "{\"code\":200,\"msg\":\"success\",\"data\":" + data + "}"; }

    @Test void sandboxGatewayAndSignatureOnGet() throws Exception {
        setup(DewuCredential.Mode.SELF_DEVELOPED, 0, ALL, List.of(), 1000);
        wm.stubFor(get(urlPathEqualTo(DewuOpenPlatformAdapter.NORMAL_LIST)).willReturn(okJson(ok(
                "{\"total\":1,\"biddings\":[{\"sku_id\":11,\"spu_id\":22,\"price\":19900,\"bidding_no\":\"B1\",\"qty\":2,\"qty_remain\":1,\"bidding_type\":0}]}"))));
        var page = adapter.listSkuListings(shop, 1, 100);
        assertThat(page.items()).singleElement().satisfies(l -> {
            assertThat(l.listingId()).isEqualTo("B1"); assertThat(l.price()).isEqualTo(19900); assertThat(l.stock()).isEqualTo(1);
        });
        LoggedRequest r = wm.findAll(getRequestedFor(urlPathEqualTo(DewuOpenPlatformAdapter.NORMAL_LIST))).get(0);
        assertThat(r.queryParameter("page_size").firstValue()).isEqualTo("30");   // 文档上限 30
        assertThat(r.queryParameter("app_key").firstValue()).isEqualTo("selfKey");
        assertThat(r.queryParameter("access_token").isPresent()).isFalse();       // 自研应用不传 token
        Map<String, Object> p = new TreeMap<>();
        r.getQueryParams().forEach((k, v) -> { if (!k.equals("sign")) p.put(k, v.firstValue()); });
        assertThat(r.queryParameter("sign").firstValue()).isEqualTo(DewuSigner.sign(p, "selfSecret"));
    }

    @Test void genericListUsedForNonNormalBiddingType() throws Exception {
        setup(DewuCredential.Mode.SELF_DEVELOPED, 7, ALL, List.of(), 1000);
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.GENERIC_LIST)).willReturn(okJson(ok("{\"total\":0,\"biddings\":[]}"))));
        adapter.listSkuListings(shop, 1, 20);
        var body = om.readTree(wm.findAll(postRequestedFor(urlPathEqualTo(DewuOpenPlatformAdapter.GENERIC_LIST))).get(0).getBodyAsString());
        assertThat(body.path("bidding_type").asInt()).isEqualTo(7);
        Map<String, Object> p = new TreeMap<>(); body.fields().forEachRemaining(e -> { if (!e.getKey().equals("sign")) p.put(e.getKey(), e.getValue().asText()); });
        assertThat(body.path("sign").asText()).isEqualTo(DewuSigner.sign(p, "selfSecret"));
    }

    @Test void normalUpdateSuccessReturnsNewBiddingNoAndVerifies() throws Exception {
        setup(DewuCredential.Mode.ISV, 0, ALL, List.of(), 1000);
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.GENERIC_DETAIL)).withRequestBody(matchingJsonPath("$.bidding_no", equalTo("B1")))
                .willReturn(okJson(ok("{\"bidding_no\":\"B1\",\"price\":20900,\"qty\":3,\"qty_remain\":2}"))));
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.GENERIC_DETAIL)).withRequestBody(matchingJsonPath("$.bidding_no", equalTo("B2")))
                .willReturn(okJson(ok("{\"bidding_no\":\"B2\",\"price\":19900,\"qty_remain\":2}"))));
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.NORMAL_UPDATE)).willReturn(okJson(ok("{\"bidding_no\":\"B2\",\"qty\":2}"))));

        UpdateResult r = adapter.updateListingPrice(shop, "B1", 19900, "idem-1");
        assertThat(r.status()).isEqualTo(UpdateStatus.ACCEPTED); assertThat(r.newListingId()).isEqualTo("B2");
        var body = om.readTree(wm.findAll(postRequestedFor(urlPathEqualTo(DewuOpenPlatformAdapter.NORMAL_UPDATE))).get(0).getBodyAsString());
        assertThat(body.path("price").asLong()).isEqualTo(19900);
        assertThat(body.path("qty").asInt()).isEqualTo(2); assertThat(body.path("old_qty").asInt()).isEqualTo(2);
        assertThat(body.path("access_token").asText()).isEqualTo("AT1"); assertThat(body.path("app_key").asText()).isEqualTo("isvKey");
        assertThat(adapter.getUpdateResult(shop, r.requestId()).effectivePrice()).isEqualTo(19900);
        // 幂等：同一键不再提交
        adapter.updateListingPrice(shop, "B1", 19900, "idem-1");
        wm.verify(1, postRequestedFor(urlPathEqualTo(DewuOpenPlatformAdapter.NORMAL_UPDATE)));
    }

    @Test void generalUpdateForOtherTypes() {
        setup(DewuCredential.Mode.SELF_DEVELOPED, 7, ALL, List.of(), 1000);
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.GENERIC_DETAIL)).willReturn(okJson(ok("{\"price\":20900}"))));
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.GENERAL_UPDATE)).willReturn(okJson(ok("{\"bidding_no\":\"G2\",\"bid_status\":1,\"tips\":\"ok\"}"))));
        assertThat(adapter.updateListingPrice(shop, "G1", 19900, "k").newListingId()).isEqualTo("G2");
    }

    @Test void priceMustEndWith900() {
        setup(DewuCredential.Mode.SELF_DEVELOPED, 0, ALL, List.of(), 1000);
        var r = adapter.updateListingPrice(shop, "B1", 19800, "k");
        assertThat(r.status()).isEqualTo(UpdateStatus.FAILED); assertThat(r.errorCode()).isEqualTo("INVALID_PRICE");
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test void noWritePackNoUpdate() {
        setup(DewuCredential.Mode.SELF_DEVELOPED, 0, Set.of("BIDDING_READ"), List.of(), 1000);
        assertThat(adapter.capabilities().updatePrice()).isFalse();
        assertThat(adapter.updateListingPrice(shop, "B1", 19900, "k").errorCode()).isEqualTo("NO_PERMISSION");
    }

    @Test void code5030IsRetryableRateLimit() {
        setup(DewuCredential.Mode.SELF_DEVELOPED, 0, ALL, List.of(), 1000);
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.GENERIC_DETAIL)).willReturn(okJson("{\"code\":5030,\"msg\":\"limit\"}")));
        assertThatThrownBy(() -> adapter.getCurrentPrice(shop, "B1")).isInstanceOfSatisfying(AdapterException.class, e -> {
            assertThat(e.code()).isEqualTo("RATE_LIMITED"); assertThat(e.retryable()).isTrue();
        });
    }

    @Test void localQuotaPerShopAndApi() {
        setup(DewuCredential.Mode.SELF_DEVELOPED, 0, ALL, List.of(), 2);
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.GENERIC_DETAIL)).willReturn(okJson(ok("{\"price\":19900}"))));
        adapter.getCurrentPrice(shop, "B1"); adapter.getCurrentPrice(shop, "B1");
        assertThatThrownBy(() -> adapter.getCurrentPrice(shop, "B1")).isInstanceOfSatisfying(AdapterException.class, e -> assertThat(e.code()).isEqualTo("LOCAL_QUOTA"));
        wm.verify(2, postRequestedFor(urlPathEqualTo(DewuOpenPlatformAdapter.GENERIC_DETAIL)));
    }

    @Test void tokenExpiredTriggersRefreshAndRetry() {
        setup(DewuCredential.Mode.ISV, 0, ALL, List.of(40001), 1000);
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.GENERIC_DETAIL)).withRequestBody(matchingJsonPath("$.access_token", equalTo("AT1")))
                .willReturn(okJson("{\"code\":40001,\"msg\":\"token expired\"}")));
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.GENERIC_DETAIL)).withRequestBody(matchingJsonPath("$.access_token", equalTo("AT2")))
                .willReturn(okJson(ok("{\"price\":29900}"))));
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.OAUTH_REFRESH)).withRequestBody(matchingJsonPath("$.refresh_token", equalTo("RT1")))
                .willReturn(okJson(ok("{\"access_token\":\"AT2\",\"refresh_token\":\"RT2\",\"access_token_expires_in\":86400}"))));
        assertThat(adapter.getCurrentPrice(shop, "B1")).contains(29900L);
        assertThat(store.c.accessToken()).isEqualTo("AT2"); assertThat(store.c.refreshToken()).isEqualTo("RT2");
    }

    @Test void proactiveRefreshBeforeExpiry() {
        setup(DewuCredential.Mode.ISV, 0, ALL, List.of(), 1000);
        store.c = new DewuCredential(1, DewuCredential.Mode.ISV, null, null, "AT1", "RT1", null, Instant.ofEpochMilli(NOW).plusSeconds(60), null, 0);
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.OAUTH_REFRESH)).willReturn(okJson(ok("{\"access_token\":\"AT9\",\"expires_in\":86400}"))));
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.GENERIC_DETAIL)).willReturn(okJson(ok("{\"price\":19900}"))));
        adapter.getCurrentPrice(shop, "B1");
        assertThat(store.c.accessToken()).isEqualTo("AT9");
        assertThat(store.c.accessExpiresAt()).isEqualTo(Instant.ofEpochMilli(NOW).plusSeconds(86400));
    }

    @Test void isvOauthCodeExchange() {
        setup(DewuCredential.Mode.ISV, 0, ALL, List.of(), 1000);
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.OAUTH_TOKEN)).withRequestBody(matchingJsonPath("$.authorization_code", equalTo("CODE")))
                .withRequestBody(matchingJsonPath("$.client_id", equalTo("isvKey")))
                .willReturn(okJson(ok("{\"access_token\":\"NEW\",\"refresh_token\":\"NR\",\"open_id\":\"o1\",\"access_token_expires_in\":86400}"))));
        var r = adapter.authorizeShop(shop, "CODE");
        assertThat(r.success()).isTrue(); assertThat(r.accessToken()).isNull();   // 不向上层返回明文
        assertThat(store.c.accessToken()).isEqualTo("NEW"); assertThat(store.c.openId()).isEqualTo("o1");
    }

    @Test void competitorPriceDisabledByDefault() {
        setup(DewuCredential.Mode.SELF_DEVELOPED, 0, Set.of("BIDDING_READ", "LOWEST_PRICE"), List.of(), 1000);
        assertThat(adapter.capabilities().competitorPrice()).isFalse();
        assertThat(adapter.getCompetitorPrice(shop, "11")).isEmpty();
        assertThat(wm.getAllServeEvents()).isEmpty();
    }

    @Test void autoBidding() throws Exception {
        setup(DewuCredential.Mode.SELF_DEVELOPED, 0, ALL, List.of(), 1000);
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.ADD_AUTO_BIDDING)).willReturn(okJson(ok("{}"))));
        assertThat(adapter.enablePlatformAutoFollow(shop, "B1", true, 18900L, 1)).isTrue();
        var b = om.readTree(wm.findAll(postRequestedFor(urlPathEqualTo(DewuOpenPlatformAdapter.ADD_AUTO_BIDDING))).get(0).getBodyAsString());
        assertThat(b.path("min_price").asLong()).isEqualTo(18900); assertThat(b.path("type").asInt()).isEqualTo(1);
    }

    @Test void businessErrorNotRetryable() {
        setup(DewuCredential.Mode.SELF_DEVELOPED, 0, ALL, List.of(), 1000);
        wm.stubFor(post(urlPathEqualTo(DewuOpenPlatformAdapter.GENERIC_DETAIL)).willReturn(okJson("{\"code\":1001,\"msg\":\"参数错误\"}")));
        assertThatThrownBy(() -> adapter.getCurrentPrice(shop, "B1")).isInstanceOfSatisfying(AdapterException.class, e -> assertThat(e.retryable()).isFalse());
    }
}
