package com.dewu.repricer.web;

import com.dewu.repricer.adapter.AdapterRegistry;
import com.dewu.repricer.adapter.PlatformAdapter;
import com.dewu.repricer.adapter.PlatformAdapter.*;
import com.dewu.repricer.repo.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class ShopService {
    private final JdbcTemplate jdbc; private final AdapterRegistry adapters; private final AuditService audit;
    public ShopService(JdbcTemplate jdbc, AdapterRegistry adapters, AuditService audit) { this.jdbc = jdbc; this.adapters = adapters; this.audit = audit; }

    public ShopContext ctx(long shopId) {
        return jdbc.queryForObject("""
            SELECT s.id, s.external_shop_id, s.owner_entity, a.access_token_enc FROM shops s
            LEFT JOIN shop_authorizations a ON a.shop_id=s.id AND a.status='VALID' WHERE s.id=? LIMIT 1""",
                (rs, n) -> new ShopContext(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), shopId);
    }
    PlatformAdapter adapter(long shopId) { return adapters.get(jdbc.queryForObject("SELECT adapter_type FROM shops WHERE id=?", String.class, shopId)); }

    /** 绑定授权。真实环境 token 须加密存储（此处列名 *_enc，生产应接入 KMS/Jasypt）。 */
    @Transactional
    public AuthResult authorize(long shopId, String code, String actor) {
        PlatformAdapter ad = adapter(shopId);
        AuthResult r = ad.authorizeShop(ctx(shopId), code);
        if ("DEWU_OPEN".equals(ad.type())) {   // 真实适配器自行加密保存 token
            audit.log(actor, "AUTHORIZE_SHOP", shopId, "SHOP", String.valueOf(shopId), Map.of("success", r.success()));
            return r;
        }
        jdbc.update("""
            INSERT INTO shop_authorizations(shop_id,auth_type,credential_ref,access_token_enc,refresh_token_enc,expires_at,status,last_checked_at,last_error)
            VALUES (?, 'OAUTH2', ?, ?, ?, now() + (? || ' seconds')::interval, ?, now(), ?)
            ON CONFLICT (shop_id, auth_type) DO UPDATE SET access_token_enc=EXCLUDED.access_token_enc, refresh_token_enc=EXCLUDED.refresh_token_enc,
              expires_at=EXCLUDED.expires_at, status=EXCLUDED.status, last_checked_at=now(), last_error=EXCLUDED.last_error""",
                shopId, "shop:" + shopId, r.accessToken(), r.refreshToken(), String.valueOf(r.expiresInSeconds()),
                r.success() ? "VALID" : "ERROR", r.success() ? null : r.message());
        audit.log(actor, "AUTHORIZE_SHOP", shopId, "SHOP", String.valueOf(shopId), Map.of("success", r.success()));
        return r;
    }

    /** 授权状态监控：即将过期标记 EXPIRING，已过期标记 EXPIRED（并暂停店铺跟价）。 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 30_000)
    public void monitorAuth() {
        jdbc.update("UPDATE shop_authorizations SET status='EXPIRING', last_checked_at=now() WHERE status='VALID' AND expires_at < now() + interval '3 days'");
        jdbc.update("UPDATE shop_authorizations SET status='EXPIRED', last_checked_at=now() WHERE status IN ('VALID','EXPIRING') AND expires_at < now()");
        jdbc.update("UPDATE shop_authorizations SET status='EXPIRED' WHERE auth_mode='ISV' AND refresh_expires_at < now() AND status<>'EXPIRED'");
        jdbc.update("UPDATE shops SET repricing_enabled=false WHERE id IN (SELECT shop_id FROM shop_authorizations WHERE status='EXPIRED') AND repricing_enabled");
    }

    /** ISV token 自动续期：提前刷新即将过期的 access_token。 */
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void autoRefreshTokens() {
        if (dewu == null) return;
        for (Long id : jdbc.queryForList("""
                SELECT a.shop_id FROM shop_authorizations a JOIN shops s ON s.id=a.shop_id
                WHERE s.adapter_type='DEWU_OPEN' AND a.auth_mode='ISV' AND a.refresh_token_enc IS NOT NULL
                  AND (a.expires_at IS NULL OR a.expires_at < now() + interval '1 hour') AND a.status<>'EXPIRED'""", Long.class)) {
            try { dewu.refreshIfNeeded(id, false); }
            catch (Exception e) { org.slf4j.LoggerFactory.getLogger(ShopService.class).warn("店铺 {} token 刷新失败: {}", id, e.getMessage()); }
        }
    }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.dewu.repricer.adapter.dewu.DewuOpenPlatformAdapter dewu;

    /** 同步商品/SKU/出价：分页拉取并 upsert。 */
    @Transactional
    public Map<String, Integer> sync(long shopId, String actor) {
        PlatformAdapter a = adapter(shopId); ShopContext c = ctx(shopId);
        int products = 0, listings = 0, page = 1;
        while (true) {
            Page<ProductDto> p = a.listProducts(c, page++, 100);
            for (ProductDto d : p.items()) {
                Long pid = jdbc.queryForObject("""
                    INSERT INTO products(spu_id,article_number,title,brand) VALUES (?,?,?,?)
                    ON CONFLICT (platform, spu_id) DO UPDATE SET title=EXCLUDED.title, article_number=EXCLUDED.article_number, brand=EXCLUDED.brand, updated_at=now()
                    RETURNING id""", Long.class, d.spuId(), d.articleNumber(), d.title(), d.brand());
                for (SkuDto s : d.skus())
                    jdbc.update("INSERT INTO product_skus(product_id,sku_id,spec,barcode) VALUES (?,?,?,?) ON CONFLICT (sku_id) DO UPDATE SET spec=EXCLUDED.spec, updated_at=now()",
                            pid, s.skuId(), s.spec(), s.barcode());
                products++;
            }
            if (p.items().isEmpty() || products >= p.total()) break;
        }
        page = 1;
        while (true) {
            Page<ListingDto> p = a.listSkuListings(c, page++, 100);
            for (ListingDto l : p.items()) {
                listings += jdbc.update("""
                    INSERT INTO shop_sku_listings(shop_id,sku_pk,external_listing_id,current_price,stock,status,last_synced_at)
                    SELECT ?, k.id, ?, ?, ?, ?, now() FROM product_skus k WHERE k.sku_id=?
                    ON CONFLICT (shop_id, sku_pk) DO UPDATE SET external_listing_id=EXCLUDED.external_listing_id, current_price=EXCLUDED.current_price,
                      stock=EXCLUDED.stock, status=EXCLUDED.status, last_synced_at=now()""",
                        shopId, l.listingId(), l.price(), l.stock(), l.status(), l.skuId());
            }
            if (p.items().isEmpty() || listings >= p.total()) break;
        }
        audit.log(actor, "SYNC_SHOP", shopId, "SHOP", String.valueOf(shopId), Map.of("products", products, "listings", listings));
        return Map.of("products", products, "listings", listings);
    }
}
