package com.dewu.repricer.adapter;

import org.springframework.stereotype.Component;
import java.util.Optional;

/** 经商家授权的第三方 ISV 服务适配骨架（如服务市场应用）。接入前需核实对方授权合法性与 SLA。 */
@Component
public class ThirdPartyAdapterSkeleton implements PlatformAdapter {
    @Override public String type() { return "THIRD_PARTY"; }
    @Override public Capabilities capabilities() { return new Capabilities(false, false, false, false, false, false, false); }
    private AdapterException todo() { return new AdapterException("NOT_IMPLEMENTED", "第三方适配器未接入", false); }
    @Override public AuthResult authorizeShop(ShopContext s, String c) { throw todo(); }
    @Override public Page<ProductDto> listProducts(ShopContext s, int p, int n) { throw todo(); }
    @Override public Page<ListingDto> listSkuListings(ShopContext s, int p, int n) { throw todo(); }
    @Override public Optional<Long> getCurrentPrice(ShopContext s, String id) { throw todo(); }
    @Override public Optional<CompetitorPrice> getCompetitorPrice(ShopContext s, String sku) { return Optional.empty(); }
    @Override public UpdateResult updateListingPrice(ShopContext s, String id, long p, String k) { throw todo(); }
    @Override public boolean enablePlatformAutoFollow(ShopContext s, String id, boolean e) { return false; }
    @Override public UpdateResult getUpdateResult(ShopContext s, String r) { throw todo(); }
}
