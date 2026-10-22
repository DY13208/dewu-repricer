package com.dewu.repricer.adapter.mock;

import com.dewu.repricer.adapter.AdapterException;
import com.dewu.repricer.adapter.PlatformAdapter;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 开发/测试用模拟适配器：内存数据，可注入失败率，模拟异步改价与回查。不访问任何外部网络。 */
@Component
public class MockPlatformAdapter implements PlatformAdapter {
    private final Map<String, Long> prices = new ConcurrentHashMap<>();
    private final Map<String, Long> competitor = new ConcurrentHashMap<>();
    private final Map<String, UpdateResult> requests = new ConcurrentHashMap<>();
    private final Map<String, UpdateResult> byIdem = new ConcurrentHashMap<>();
    private volatile double failureRate = 0.0;
    private final Random random = new Random(42);

    public MockPlatformAdapter() { seed(5, 20); }

    public void seed(int spus, int skusPerSpu) {
        for (int i = 1; i <= spus; i++) for (int j = 1; j <= skusPerSpu; j++) {
            String sku = "SKU" + i + "-" + j;
            prices.putIfAbsent("L-" + sku, 100_000L + i * 1000 + j * 100);
            competitor.putIfAbsent(sku, 98_000L + i * 1000 + j * 100);
        }
    }
    public void setFailureRate(double r) { failureRate = r; }
    public void setCompetitor(String skuId, Long price) { if (price == null) competitor.remove(skuId); else competitor.put(skuId, price); }
    public void setPrice(String listingId, long p) { prices.put(listingId, p); }

    @Override public String type() { return "MOCK"; }
    @Override public Capabilities capabilities() { return new Capabilities(true, true, true, true, true, false, true); }
    @Override public AuthResult authorizeShop(ShopContext shop, String code) { return new AuthResult(true, "mock-token-" + shop.shopId(), "mock-refresh", 86400 * 30, "模拟授权成功"); }

    @Override public Page<ProductDto> listProducts(ShopContext shop, int pageNo, int pageSize) {
        Map<String, List<SkuDto>> bySpu = new TreeMap<>();
        for (String l : new TreeSet<>(prices.keySet())) {
            String sku = l.substring(2); String spu = sku.substring(0, sku.indexOf('-'));
            bySpu.computeIfAbsent(spu, k -> new ArrayList<>()).add(new SkuDto(sku, "尺码 " + sku.substring(sku.indexOf('-') + 1), null));
        }
        List<ProductDto> all = bySpu.entrySet().stream().map(e -> new ProductDto(e.getKey(), "ART-" + e.getKey(), "模拟商品 " + e.getKey(), "MockBrand", e.getValue())).toList();
        return new Page<>(slice(all, pageNo, pageSize), all.size());
    }
    @Override public Page<ListingDto> listSkuListings(ShopContext shop, int pageNo, int pageSize) {
        List<ListingDto> all = new TreeSet<>(prices.keySet()).stream().map(l -> {
            String sku = l.substring(2);
            return new ListingDto(l, sku.substring(0, sku.indexOf('-')), sku, prices.get(l), 1, "ON_SALE");
        }).toList();
        return new Page<>(slice(all, pageNo, pageSize), all.size());
    }
    private static <T> List<T> slice(List<T> all, int pageNo, int size) {
        int from = Math.min((pageNo - 1) * size, all.size()); return all.subList(from, Math.min(from + size, all.size()));
    }
    @Override public Optional<Long> getCurrentPrice(ShopContext shop, String listingId) { maybeFail(); return Optional.ofNullable(prices.get(listingId)); }
    @Override public Optional<CompetitorPrice> getCompetitorPrice(ShopContext shop, String skuId) {
        maybeFail(); Long p = competitor.get(skuId);
        return p == null ? Optional.empty() : Optional.of(new CompetitorPrice(skuId, p, "mock-seller", "MOCK"));
    }
    @Override public UpdateResult updateListingPrice(ShopContext shop, String listingId, long newPrice, String idem) {
        UpdateResult prev = byIdem.get(idem);
        if (prev != null) return prev;                       // 幂等：同一键只执行一次
        maybeFail();
        if (!prices.containsKey(listingId)) return new UpdateResult(UpdateStatus.FAILED, null, null, "NOT_FOUND", "出价不存在");
        prices.put(listingId, newPrice);
        String rid = UUID.randomUUID().toString();
        UpdateResult r = new UpdateResult(UpdateStatus.ACCEPTED, rid, null, null, "已受理");
        requests.put(rid, new UpdateResult(UpdateStatus.SUCCESS, rid, newPrice, null, "生效"));
        byIdem.put(idem, r);
        return r;
    }
    @Override public boolean enablePlatformAutoFollow(ShopContext shop, String listingId, boolean enable) { return false; }
    @Override public UpdateResult getUpdateResult(ShopContext shop, String requestId) {
        return requests.getOrDefault(requestId, new UpdateResult(UpdateStatus.FAILED, requestId, null, "UNKNOWN", "未知请求"));
    }
    private void maybeFail() {
        if (failureRate > 0 && random.nextDouble() < failureRate) throw new AdapterException("RATE_LIMITED", "模拟限流", true);
    }
}
