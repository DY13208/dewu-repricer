package com.dewu.repricer.repo;

import com.dewu.repricer.engine.Strategy;
import com.dewu.repricer.task.RepricingStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcRepricingStore implements RepricingStore {
    private final JdbcTemplate jdbc;
    public JdbcRepricingStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 策略解析优先级：LISTING > PRODUCT > SHOP，同 scope 取 priority 最大。 */
    private static final String LOAD = """
        SELECT i.id, i.task_id, t.dry_run, t.priority, i.attempts, i.idempotency_key, i.status,
               s.id shop_id, s.status shop_status, s.repricing_enabled, s.adapter_type, s.external_shop_id, s.owner_entity,
               a.access_token_enc, l.id listing_id, l.external_listing_id, l.sku_pk, k.sku_id, l.current_price,
               l.repricing_excluded, l.min_profit,
               (SELECT c.purchase_cost + c.extra_cost FROM sku_costs c WHERE c.sku_pk = l.sku_pk AND (c.shop_id = s.id OR c.shop_id IS NULL)
                  ORDER BY c.shop_id NULLS LAST, c.effective_from DESC LIMIT 1) cost,
               (SELECT sa.strategy_id FROM strategy_assignments sa JOIN pricing_strategies ps ON ps.id = sa.strategy_id AND ps.enabled
                  WHERE sa.shop_id = s.id AND ((sa.scope='LISTING' AND sa.target_id=l.id) OR (sa.scope='PRODUCT' AND sa.target_id=k.product_id) OR sa.scope='SHOP')
                  ORDER BY CASE sa.scope WHEN 'LISTING' THEN 0 WHEN 'PRODUCT' THEN 1 ELSE 2 END, sa.priority DESC LIMIT 1) strategy_id
        FROM repricing_task_items i JOIN repricing_tasks t ON t.id=i.task_id
        JOIN shop_sku_listings l ON l.id=i.listing_id JOIN shops s ON s.id=l.shop_id JOIN product_skus k ON k.id=l.sku_pk
        LEFT JOIN shop_authorizations a ON a.shop_id=s.id AND a.status='VALID'
        WHERE i.id=? LIMIT 1""";

    @Override public Optional<ItemContext> loadItem(long itemId) {
        List<ItemContext> r = jdbc.query(LOAD, (rs, n) -> {
            Long sid = (Long) rs.getObject("strategy_id");
            return new ItemContext(rs.getLong("id"), rs.getLong("task_id"), rs.getBoolean("dry_run"), rs.getInt("priority"), rs.getInt("attempts"),
                    rs.getString("idempotency_key"), rs.getString("status"), rs.getLong("shop_id"), rs.getString("shop_status"),
                    rs.getBoolean("repricing_enabled"), rs.getString("adapter_type"), rs.getString("external_shop_id"), rs.getString("owner_entity"),
                    rs.getString("access_token_enc"), rs.getLong("listing_id"), rs.getString("external_listing_id"), rs.getLong("sku_pk"),
                    rs.getString("sku_id"), (Long) rs.getObject("current_price"), rs.getBoolean("repricing_excluded"),
                    (Long) rs.getObject("min_profit"), (Long) rs.getObject("cost"), sid == null ? null : strategy(sid));
        }, itemId);
        return r.stream().findFirst();
    }
    public Strategy strategy(long id) {
        return jdbc.queryForObject("SELECT * FROM pricing_strategies WHERE id=?", (rs, n) -> mapStrategy(rs), id);
    }
    public static Strategy mapStrategy(ResultSet rs) throws SQLException {
        return new Strategy(rs.getLong("id"), Strategy.Mode.valueOf(rs.getString("mode")), Strategy.OffsetType.valueOf(rs.getString("offset_type")),
                rs.getBigDecimal("offset_value"), (Long) rs.getObject("min_profit"), (Long) rs.getObject("min_price"), (Long) rs.getObject("max_price"),
                rs.getBigDecimal("fee_rate"), rs.getLong("fee_min"), (Long) rs.getObject("fee_max"), rs.getLong("fulfillment_fee"),
                Optional.ofNullable(rs.getBigDecimal("max_change_ratio")).orElse(new BigDecimal("0.15")), rs.getLong("price_step"),
                Strategy.PriceRule.valueOf(Optional.ofNullable(rs.getString("price_rule")).orElse("NONE")));
    }
    private String sw(String name) { return jdbc.queryForObject("SELECT value FROM system_switches WHERE name=?", String.class, name); }
    @Override public boolean emergencyStopped() { return "ON".equals(sw("EMERGENCY_STOP")); }
    @Override public boolean globalPaused() { return !"RUNNING".equals(sw("GLOBAL_REPRICING")); }
    @Override public boolean ownEntityAtOrBelow(String owner, long skuPk, long price) {
        Integer n = jdbc.queryForObject("""
            SELECT count(*) FROM shop_sku_listings l JOIN shops s ON s.id=l.shop_id
            WHERE s.owner_entity=? AND l.sku_pk=? AND l.status='ON_SALE' AND l.current_price<=?""", Integer.class, owner, skuPk, price);
        return n != null && n > 0;
    }
    @Override public void saveSnapshot(long shopId, long skuPk, Long lowest, boolean own, String source) {
        jdbc.update("INSERT INTO competitor_price_snapshots(shop_id,sku_pk,lowest_price,lowest_is_own_entity,source) VALUES (?,?,?,?,?)", shopId, skuPk, lowest, own, source);
    }
    @Override public void markItem(long id, String st, Long old, Long tgt, Long comp, String reason, int attempts, Long next, String rid, String err) {
        jdbc.update("""
            UPDATE repricing_task_items SET status=?, old_price=COALESCE(?,old_price), target_price=COALESCE(?,target_price),
              competitor_price=COALESCE(?,competitor_price), decision_reason=COALESCE(?,decision_reason), attempts=?, next_retry_at=?,
              external_request_id=COALESCE(?,external_request_id), last_error=?, updated_at=now() WHERE id=?""",
                st, old, tgt, comp, reason, attempts, next == null ? null : new Timestamp(next), rid, err, id);
    }
    @Override @Transactional public void applyPrice(long shopId, long listingId, long itemId, String newExt, Long old, long np, Long verified, Long comp, Long sid, String reason) {
        jdbc.update("UPDATE shop_sku_listings SET current_price=?, external_listing_id=COALESCE(?, external_listing_id), last_repriced_at=now(), version=version+1 WHERE id=?",
                verified != null ? verified : np, newExt, listingId);
        jdbc.update("INSERT INTO repricing_history(shop_id,listing_id,task_item_id,old_price,new_price,verified_price,competitor_price,strategy_id,reason) VALUES (?,?,?,?,?,?,?,?,?)",
                shopId, listingId, itemId, old, np, verified, comp, sid, reason);
    }
    @Override public void flagAnomaly(long listingId, String flag) {
        jdbc.update("UPDATE shop_sku_listings SET anomaly_flag=? WHERE id=?", flag.length() > 64 ? flag.substring(0, 64) : flag, listingId);
    }
    @Override public void logApi(Long shopId, String adapter, String op, boolean ok, long ms, String code, String msg) {
        jdbc.update("INSERT INTO api_call_logs(shop_id,adapter,operation,success,latency_ms,error_code,error_msg) VALUES (?,?,?,?,?,?,?)", shopId, adapter, op, ok, (int) ms, code, msg);
    }
    @Override public void refreshTaskCounters(long taskId) {
        jdbc.update("""
            UPDATE repricing_tasks t SET
              succeeded=(SELECT count(*) FROM repricing_task_items WHERE task_id=t.id AND status IN ('VERIFIED','DRY_RUN')),
              failed=(SELECT count(*) FROM repricing_task_items WHERE task_id=t.id AND status IN ('FAILED','BLOCKED','UNVERIFIED')),
              skipped=(SELECT count(*) FROM repricing_task_items WHERE task_id=t.id AND status='SKIPPED'),
              status=CASE WHEN EXISTS(SELECT 1 FROM repricing_task_items WHERE task_id=t.id AND status IN ('PENDING','RETRY')) THEN 'RUNNING'
                          WHEN EXISTS(SELECT 1 FROM repricing_task_items WHERE task_id=t.id AND status IN ('FAILED','BLOCKED','UNVERIFIED')) THEN 'PARTIAL'
                          ELSE 'SUCCEEDED' END,
              finished_at=CASE WHEN EXISTS(SELECT 1 FROM repricing_task_items WHERE task_id=t.id AND status IN ('PENDING','RETRY')) THEN NULL ELSE now() END
            WHERE id=? AND status<>'CANCELLED'""", taskId);
    }
}
