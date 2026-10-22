package com.dewu.repricer.adapter;

import java.util.List;
import java.util.Optional;

/**
 * 统一平台适配层。所有平台访问必须经过此接口；实现只允许使用平台官方授权接口或经授权的第三方服务。
 * 金额单位：分。
 */
public interface PlatformAdapter {
    String type();
    Capabilities capabilities();
    AuthResult authorizeShop(ShopContext shop, String authCode);
    Page<ProductDto> listProducts(ShopContext shop, int pageNo, int pageSize);
    Page<ListingDto> listSkuListings(ShopContext shop, int pageNo, int pageSize);
    Optional<Long> getCurrentPrice(ShopContext shop, String listingId);
    /** 仅当 capabilities().competitorPrice() 为 true 时可调用，否则返回 empty。 */
    Optional<CompetitorPrice> getCompetitorPrice(ShopContext shop, String skuId);
    UpdateResult updateListingPrice(ShopContext shop, String listingId, long newPrice, String idempotencyKey);
    /** 仅当平台提供官方自动跟价能力时可用。 */
    boolean enablePlatformAutoFollow(ShopContext shop, String listingId, boolean enable);
    UpdateResult getUpdateResult(ShopContext shop, String requestId);

    record Capabilities(boolean listProducts, boolean listListings, boolean currentPrice, boolean competitorPrice,
                        boolean updatePrice, boolean platformAutoFollow, boolean asyncUpdate) {}
    record ShopContext(long shopId, String externalShopId, String ownerEntity, String accessToken) {}
    record AuthResult(boolean success, String accessToken, String refreshToken, long expiresInSeconds, String message) {}
    record Page<T>(List<T> items, int total) {}
    record ProductDto(String spuId, String articleNumber, String title, String brand, List<SkuDto> skus) {}
    record SkuDto(String skuId, String spec, String barcode) {}
    record ListingDto(String listingId, String spuId, String skuId, long price, int stock, String status) {}
    record CompetitorPrice(String skuId, Long lowestPrice, String lowestSellerRef, String source) {}
    enum UpdateStatus { ACCEPTED, SUCCESS, FAILED, RETRYABLE }
    record UpdateResult(UpdateStatus status, String requestId, Long effectivePrice, String errorCode, String message) {}
}
