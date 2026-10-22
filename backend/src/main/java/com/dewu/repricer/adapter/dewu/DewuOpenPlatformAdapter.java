package com.dewu.repricer.adapter.dewu;

import com.dewu.repricer.adapter.AdapterException;
import com.dewu.repricer.adapter.PlatformAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 得物开放平台真实适配器（依据 docs/07 官方文档核实结果实现）。
 * 状态：代码已实现并通过 WireMock 契约测试，<b>尚未用真实凭据联调</b>；最低价接口参数待控制台核实（默认关闭）。
 * 严禁使用商家后台/App 私有接口、抓包签名或模拟客户端。
 */
@Component
public class DewuOpenPlatformAdapter implements PlatformAdapter {
    private static final Logger log = LoggerFactory.getLogger(DewuOpenPlatformAdapter.class);
    static final String NORMAL_LIST = "/dop/api/v1/bidding/normal/normal_list";
    static final String GENERIC_LIST = "/dop/api/v1/bidding/generic_list";
    static final String GENERIC_DETAIL = "/dop/api/v1/bidding/generic_detail";
    static final String NORMAL_UPDATE = "/dop/api/v1/bidding/normal/normal_update";
    static final String GENERAL_UPDATE = "/dop/api/v1/bidding/general/update_bidding";
    static final String ADD_AUTO_BIDDING = "/dop/api/v1/bidding/add_auto_bidding";
    static final String OAUTH_TOKEN = "/api/v1/h5/passport/v1/oauth2/token";
    static final String OAUTH_REFRESH = "/api/v1/h5/passport/v1/oauth2/refresh_token";
    /** 各出价类型最低价接口路径（来自解决方案文档；参数待控制台核实）。 */
    static final Map<Integer, String> LOWEST_PRICE_PATHS = Map.of(
            0, "/dop/api/v1/bidding/normal/normal_lowest_price",
            7, "/dop/api/v1/bidding/fast/fast_lowest_price",
            1, "/dop/api/v1/bidding/normal_pre_sale/lowest_price",
            12, "/dop/api/v1/bidding/brand/brand_lowest_price",
            13, "/dop/api/v1/bidding/brand/brand_lowest_price",
            6, "/dop/api/v1/consign/bidding/lowest_price",
            25, "/dop/api/v1/consign/bidding/lowest_price",
            3, "/dop/api/v1/bidding/oversea/oversea_lowest_price");

    private final DewuOpenProperties props; private final DewuCredentialStore creds; private final DewuHttpClient client; private final Clock clock;
    private final Map<String, UpdateResult> idempotent = new ConcurrentHashMap<>();

    @Autowired
    public DewuOpenPlatformAdapter(DewuOpenProperties props, DewuCredentialStore creds, JdbcQuotaTracker quota) {
        this(props, creds, new DewuHttpClient(props, quota, System::currentTimeMillis), Clock.systemUTC());
    }
    public DewuOpenPlatformAdapter(DewuOpenProperties props, DewuCredentialStore creds, DewuHttpClient client, Clock clock) {
        this.props = props; this.creds = creds; this.client = client; this.clock = clock;
    }

    @Override public String type() { return "DEWU_OPEN"; }
    /** 能力声明由已获批的权限包（repricer.dewu-open.granted-packs）决定。 */
    @Override public Capabilities capabilities() {
        boolean read = props.has("BIDDING_READ");
        return new Capabilities(read, read, read, props.has("LOWEST_PRICE") && props.lowestPriceEnabled(),
                props.has("BIDDING_WRITE"), props.has("AUTO_BIDDING"), false);
    }

    // ---------------- 授权 ----------------
    @Override public AuthResult authorizeShop(ShopContext shop, String authCode) {
        DewuCredential c = cred(shop.shopId(), false);
        if (c.mode() == DewuCredential.Mode.SELF_DEVELOPED) {
            boolean ok = notBlank(c.appKey()) && notBlank(c.appSecret());
            return new AuthResult(ok, null, null, 0, ok ? "自研商家应用：使用店铺 app_key/app_secret，无需 OAuth" : "缺少 app_key/app_secret");
        }
        requireIsvApp();
        if (!notBlank(authCode)) return new AuthResult(false, null, null, 0, "ISV 模式需要服务市场授权回调的 code");
        JsonNode d = client.oauth(OAUTH_TOKEN, Map.of("client_id", props.isvAppKey(), "client_secret", props.isvAppSecret(), "authorization_code", authCode));
        return saveTokenResponse(shop.shopId(), d);
    }

    /** 若 ISV token 即将过期则刷新；返回最新凭据。定时任务也会调用以自动续期。 */
    public DewuCredential refreshIfNeeded(long shopId, boolean force) {
        DewuCredential c = cred(shopId, false);
        if (c.mode() != DewuCredential.Mode.ISV) return c;
        Instant limit = clock.instant().plusSeconds(props.tokenRefreshAheadSeconds());
        if (!force && c.accessToken() != null && c.accessExpiresAt() != null && c.accessExpiresAt().isAfter(limit)) return c;
        if (!notBlank(c.refreshToken())) { creds.markError(shopId, "EXPIRED", "无 refresh_token，需重新授权"); throw new AdapterException("AUTH_REQUIRED", "需重新授权", false); }
        if (c.refreshExpiresAt() != null && c.refreshExpiresAt().isBefore(clock.instant())) {
            creds.markError(shopId, "EXPIRED", "refresh_token 已过期"); throw new AdapterException("AUTH_REQUIRED", "refresh_token 已过期，需重新授权", false);
        }
        requireIsvApp();
        try {
            JsonNode d = client.oauth(OAUTH_REFRESH, Map.of("client_id", props.isvAppKey(), "client_secret", props.isvAppSecret(), "refresh_token", c.refreshToken()));
            saveTokenResponse(shopId, d);
            log.info("店铺 {} access_token 已刷新", shopId);
        } catch (AdapterException e) {
            if (!e.retryable()) creds.markError(shopId, "ERROR", "刷新 token 失败: " + e.getMessage());
            throw e;
        }
        return cred(shopId, false);
    }

    /** 字段名按文档：access_token / refresh_token / open_id + 过期时间（字段名与单位待联调核实，兼容几种写法）。 */
    private AuthResult saveTokenResponse(long shopId, JsonNode d) {
        String at = d.path("access_token").asText(null);
        if (at == null) return new AuthResult(false, null, null, 0, "OAuth 响应缺少 access_token");
        String rt = d.path("refresh_token").asText(null);
        long exp = firstPositive(d, 86400, "access_token_expires_in", "expires_in");
        long rexp = firstPositive(d, 180L * 86400, "refresh_token_expires_in", "refresh_expires_in");
        Instant now = clock.instant();
        creds.saveTokens(shopId, at, rt, d.path("open_id").asText(null), now.plusSeconds(exp), rt == null ? null : now.plusSeconds(rexp));
        return new AuthResult(true, null, null, exp, "OAuth 授权成功");   // token 已加密落库，不向上层返回明文
    }
    private static long firstPositive(JsonNode d, long def, String... names) {
        for (String n : names) { long v = d.path(n).asLong(0); if (v > 0) return v > 10_000_000_000L ? v / 1000 : v; }
        return def;
    }

    // ---------------- 出价查询 ----------------
    @Override public Page<ProductDto> listProducts(ShopContext shop, int pageNo, int pageSize) {
        // SPU 归平台所有，商家仅拥有出价；product_pool/list 参数未核实，故由出价列表聚合 SPU/SKU。
        Page<ListingDto> l = listSkuListings(shop, pageNo, pageSize);
        Map<String, List<SkuDto>> by = new LinkedHashMap<>();
        l.items().forEach(x -> by.computeIfAbsent(x.spuId(), k -> new ArrayList<>()).add(new SkuDto(x.skuId(), null, null)));
        List<ProductDto> items = by.entrySet().stream().map(e -> new ProductDto(e.getKey(), null, "SPU " + e.getKey(), null, e.getValue())).toList();
        return new Page<>(items, l.items().isEmpty() ? 0 : Integer.MAX_VALUE);
    }

    @Override public Page<ListingDto> listSkuListings(ShopContext shop, int pageNo, int pageSize) {
        DewuCredential c = cred(shop.shopId(), true);
        int size = Math.min(30, Math.max(1, pageSize));
        JsonNode d;
        if (c.biddingType() == 0) {
            d = call(c, "GET", NORMAL_LIST, Map.of("page_no", pageNo, "page_size", size));
        } else {
            d = call(c, "POST", GENERIC_LIST, Map.of("bidding_type", c.biddingType(), "page_no", pageNo, "page_size", size));
        }
        JsonNode arr = d.has("biddings") ? d.path("biddings") : d.path("list");
        List<ListingDto> items = new ArrayList<>();
        for (JsonNode b : arr) {
            int remain = b.path("qty_remain").asInt(b.path("qty").asInt(0));
            items.add(new ListingDto(b.path("bidding_no").asText(), b.path("spu_id").asText(), b.path("sku_id").asText(),
                    b.path("price").asLong(), remain, remain > 0 ? "ON_SALE" : "SOLD_OUT"));
        }
        int total = d.path("total").asInt(items.size() < size ? (pageNo - 1) * size + items.size() : Integer.MAX_VALUE);
        return new Page<>(items, total);
    }

    @Override public Optional<Long> getCurrentPrice(ShopContext shop, String biddingNo) {
        JsonNode d = detail(cred(shop.shopId(), true), biddingNo);
        return d.hasNonNull("price") ? Optional.of(d.path("price").asLong()) : Optional.empty();
    }
    private JsonNode detail(DewuCredential c, String biddingNo) { return call(c, "POST", GENERIC_DETAIL, Map.of("bidding_no", biddingNo)); }

    /** 最低价：路径来自解决方案文档，参数（此处假设 sku_id）【待控制台核实】；默认关闭，未开启时返回 empty → 引擎不跟价。 */
    @Override public Optional<CompetitorPrice> getCompetitorPrice(ShopContext shop, String skuId) {
        if (!capabilities().competitorPrice()) return Optional.empty();
        DewuCredential c = cred(shop.shopId(), true);
        String path = LOWEST_PRICE_PATHS.get(c.biddingType());
        if (path == null) return Optional.empty();
        JsonNode d = call(c, "POST", path, Map.of("sku_id", skuId));   // TODO 待控制台核实：参数名、是否需 spu_id、响应字段
        JsonNode p = d.has("lowest_price") ? d.path("lowest_price") : d.path("price");
        return p.isMissingNode() || p.isNull() ? Optional.empty() : Optional.of(new CompetitorPrice(skuId, p.asLong(), null, "DEWU_LOWEST_PRICE"));
    }

    // ---------------- 改价 ----------------
    @Override public UpdateResult updateListingPrice(ShopContext shop, String biddingNo, long newPrice, String idemKey) {
        UpdateResult prev = idempotent.get(idemKey);
        if (prev != null) return prev;
        if (!capabilities().updatePrice()) return new UpdateResult(UpdateStatus.FAILED, null, null, "NO_PERMISSION", "未获批出价写权限包");
        if (newPrice <= 0 || newPrice % 1000 != 900) return new UpdateResult(UpdateStatus.FAILED, null, null, "INVALID_PRICE", "得物要求价格（分）以 900 结尾: " + newPrice);
        DewuCredential c = cred(shop.shopId(), true);
        JsonNode cur = detail(c, biddingNo);
        if (cur.path("price").asLong(-1) == newPrice) {   // 幂等：已是目标价则不再提交
            UpdateResult r = new UpdateResult(UpdateStatus.SUCCESS, biddingNo, newPrice, null, "当前已是目标价", biddingNo);
            idempotent.put(idemKey, r); return r;
        }
        Map<String, Object> p = new HashMap<>();
        p.put("bidding_no", biddingNo); p.put("price", newPrice);
        JsonNode d;
        if (c.biddingType() == 0) {
            int qty = cur.path("qty_remain").asInt(cur.path("qty").asInt(0));
            if (qty < 1) return new UpdateResult(UpdateStatus.FAILED, null, null, "QTY_ZERO", "剩余数量为 0，平台不允许改为 0，跳过");
            p.put("qty", qty); p.put("old_qty", qty);
            d = call(c, "POST", NORMAL_UPDATE, p);
        } else {
            d = call(c, "POST", GENERAL_UPDATE, p);   // 类型 6/13/3/23/25 需 inventory_list，待按业务补充
        }
        String newNo = d.path("bidding_no").asText(null);
        if (newNo == null || newNo.isBlank()) return new UpdateResult(UpdateStatus.FAILED, null, null, "NO_BIDDING_NO", "改价响应缺少新 bidding_no");
        String tips = d.path("risk_toast").asText(d.path("tips").asText("已提交"));
        UpdateResult r = new UpdateResult(UpdateStatus.ACCEPTED, newNo, null, null, tips, newNo);
        idempotent.put(idemKey, r);
        return r;
    }

    /** 改价回查：requestId 即新 bidding_no，用 detail 读取实际价格。 */
    @Override public UpdateResult getUpdateResult(ShopContext shop, String newBiddingNo) {
        JsonNode d = detail(cred(shop.shopId(), true), newBiddingNo);
        return d.hasNonNull("price")
                ? new UpdateResult(UpdateStatus.SUCCESS, newBiddingNo, d.path("price").asLong(), null, "回查成功", newBiddingNo)
                : new UpdateResult(UpdateStatus.FAILED, newBiddingNo, null, "NO_PRICE", "详情无价格", newBiddingNo);
    }

    @Override public boolean enablePlatformAutoFollow(ShopContext shop, String listingId, boolean enable) {
        throw new AdapterException("BAD_ARGS", "官方自动跟价需要 min_price 与 type，请使用带参数版本", false);
    }
    /** add_auto_bidding：type 1=跟平台最低，2=比平台最低低一档；auto_switch 取值格式待核实（此处 1/0）。 */
    @Override public boolean enablePlatformAutoFollow(ShopContext shop, String biddingNo, boolean enable, Long minPrice, int type) {
        if (!capabilities().platformAutoFollow()) return false;
        if (minPrice == null || minPrice % 1000 != 900) throw new AdapterException("INVALID_PRICE", "min_price 需以 900 结尾", false);
        call(cred(shop.shopId(), true), "POST", ADD_AUTO_BIDDING, Map.of("bidding_no", biddingNo, "min_price", minPrice, "type", type, "auto_switch", enable ? 1 : 0));
        return true;
    }

    // ---------------- 内部 ----------------
    /** 统一调用：token 过期时自动刷新并重试一次。 */
    private JsonNode call(DewuCredential c, String method, String path, Map<String, Object> biz) {
        try {
            return doCall(c, method, path, biz);
        } catch (AdapterException e) {
            if (!"TOKEN_EXPIRED".equals(e.code()) || c.mode() != DewuCredential.Mode.ISV) throw e;
            DewuCredential fresh = refreshIfNeeded(c.shopId(), true);
            return doCall(fresh, method, path, biz);
        }
    }
    private JsonNode doCall(DewuCredential c, String method, String path, Map<String, Object> biz) {
        if (c.mode() == DewuCredential.Mode.ISV)
            return client.call(c.shopId(), method, path, biz, props.isvAppKey(), props.isvAppSecret(), c.accessToken());
        return client.call(c.shopId(), method, path, biz, c.appKey(), c.appSecret(), null);
    }
    private DewuCredential cred(long shopId, boolean refresh) {
        if (refresh) {
            DewuCredential c = creds.find(shopId).orElseThrow(() -> new AdapterException("NO_CREDENTIAL", "店铺未配置得物凭据", false));
            return c.mode() == DewuCredential.Mode.ISV ? refreshIfNeeded(shopId, false) : c;
        }
        return creds.find(shopId).orElseThrow(() -> new AdapterException("NO_CREDENTIAL", "店铺未配置得物凭据", false));
    }
    private void requireIsvApp() {
        if (!notBlank(props.isvAppKey()) || !notBlank(props.isvAppSecret())) throw new AdapterException("NO_ISV_APP", "未配置 ISV client_id/client_secret", false);
    }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
