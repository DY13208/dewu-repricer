package com.dewu.repricer.web;

import com.dewu.repricer.adapter.AdapterRegistry;
import com.dewu.repricer.adapter.mock.MockPlatformAdapter;
import com.dewu.repricer.repo.AuditService;
import com.dewu.repricer.task.TaskService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final JdbcTemplate jdbc; private final ShopScope scope; private final ShopService shops; private final TaskService tasks;
    private final CostImportService costs; private final AuditService audit; private final AdapterRegistry adapters; private final MockPlatformAdapter mock;
    public ApiController(JdbcTemplate jdbc, ShopScope scope, ShopService shops, TaskService tasks, CostImportService costs, AuditService audit,
                         AdapterRegistry adapters, MockPlatformAdapter mock) {
        this.jdbc = jdbc; this.scope = scope; this.shops = shops; this.tasks = tasks; this.costs = costs; this.audit = audit; this.adapters = adapters; this.mock = mock;
    }

    // ---------- 经营总览 ----------
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestHeader(value = "X-User", required = false) String u) {
        Long[] ids = scope.allowedShopIds(u);
        Map<String, Object> m = new LinkedHashMap<>(jdbc.queryForMap("""
            SELECT (SELECT count(*) FROM shops WHERE id = ANY(?)) shops,
                   (SELECT count(*) FROM shop_sku_listings WHERE shop_id = ANY(?)) listings,
                   (SELECT count(*) FROM shop_sku_listings WHERE shop_id = ANY(?) AND anomaly_flag IS NOT NULL) anomalies,
                   (SELECT count(*) FROM repricing_history WHERE shop_id = ANY(?) AND created_at > now() - interval '24 hours') changes24h,
                   (SELECT count(*) FROM api_call_logs WHERE shop_id = ANY(?) AND NOT success AND created_at > now() - interval '24 hours') apiErrors24h""",
                ids, ids, ids, ids, ids));
        m.put("switches", jdbc.queryForList("SELECT name, value FROM system_switches"));
        m.put("authAlerts", jdbc.queryForList("SELECT shop_id, status, expires_at FROM shop_authorizations WHERE status <> 'VALID' AND shop_id = ANY(?)", (Object) ids));
        return m;
    }

    // ---------- 店铺 ----------
    @GetMapping("/shops")
    public List<Map<String, Object>> shops(@RequestHeader(value = "X-User", required = false) String u) {
        return jdbc.queryForList("""
            SELECT s.*, g.name group_name, a.status auth_status, a.expires_at auth_expires_at,
                   (SELECT count(*) FROM shop_sku_listings l WHERE l.shop_id=s.id) listing_count
            FROM shops s LEFT JOIN shop_groups g ON g.id=s.group_id LEFT JOIN shop_authorizations a ON a.shop_id=s.id
            WHERE s.id = ANY(?) ORDER BY s.id""", (Object) scope.allowedShopIds(u));
    }
    public record ShopReq(String name, String adapterType, String externalShopId, String ownerEntity, String groupName,
                          Integer scanIntervalSeconds, Double qpsLimit) {}
    @PostMapping("/shops")
    public Map<String, Object> createShop(@RequestBody ShopReq r, @RequestHeader(value = "X-User", required = false) String u) {
        if (!adapters.types().contains(r.adapterType())) throw new IllegalArgumentException("未知适配器类型");
        Long gid = r.groupName() == null || r.groupName().isBlank() ? null : jdbc.queryForObject(
                "INSERT INTO shop_groups(name) VALUES (?) ON CONFLICT (name) DO UPDATE SET name=EXCLUDED.name RETURNING id", Long.class, r.groupName());
        Long id = jdbc.queryForObject("INSERT INTO shops(name,adapter_type,external_shop_id,owner_entity,group_id,scan_interval_seconds,qps_limit) VALUES (?,?,?,?,?,?,?) RETURNING id",
                Long.class, r.name(), r.adapterType(), r.externalShopId(), r.ownerEntity(), gid,
                Optional.ofNullable(r.scanIntervalSeconds()).orElse(600), Optional.ofNullable(r.qpsLimit()).orElse(1.0));
        audit.log(u, "CREATE_SHOP", id, "SHOP", String.valueOf(id), Map.of("name", r.name()));
        return Map.of("id", id);
    }
    @PostMapping("/shops/{id}/authorize")
    public Object authorize(@PathVariable long id, @RequestBody(required = false) Map<String, String> b, @RequestHeader(value = "X-User", required = false) String u) {
        scope.check(u, id); return shops.authorize(id, b == null ? null : b.get("code"), u);
    }
    @PostMapping("/shops/{id}/sync")
    public Object sync(@PathVariable long id, @RequestHeader(value = "X-User", required = false) String u) { scope.check(u, id); return shops.sync(id, u); }
    @PostMapping("/shops/{id}/repricing")
    public void toggleShop(@PathVariable long id, @RequestParam boolean enabled, @RequestHeader(value = "X-User", required = false) String u) {
        scope.check(u, id);
        jdbc.update("UPDATE shops SET repricing_enabled=?, updated_at=now() WHERE id=?", enabled, id);
        audit.log(u, enabled ? "RESUME_SHOP" : "PAUSE_SHOP", id, "SHOP", String.valueOf(id), Map.of());
    }

    // ---------- SKU / 出价 ----------
    @GetMapping("/listings")
    public Map<String, Object> listings(@RequestParam(required = false) Long shopId, @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) Boolean anomaly, @RequestParam(required = false) Boolean excluded,
                                        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
                                        @RequestHeader(value = "X-User", required = false) String u) {
        StringBuilder w = new StringBuilder(" WHERE l.shop_id = ANY(?)"); List<Object> args = new ArrayList<>(); args.add(scope.allowedShopIds(u));
        if (shopId != null) { w.append(" AND l.shop_id=?"); args.add(shopId); }
        if (keyword != null && !keyword.isBlank()) { w.append(" AND (p.title ILIKE ? OR p.article_number ILIKE ? OR k.sku_id ILIKE ?)"); String k = "%" + keyword + "%"; args.add(k); args.add(k); args.add(k); }
        if (Boolean.TRUE.equals(anomaly)) w.append(" AND l.anomaly_flag IS NOT NULL");
        if (excluded != null) { w.append(" AND l.repricing_excluded=?"); args.add(excluded); }
        String from = " FROM shop_sku_listings l JOIN product_skus k ON k.id=l.sku_pk JOIN products p ON p.id=k.product_id JOIN shops s ON s.id=l.shop_id";
        Integer total = jdbc.queryForObject("SELECT count(*)" + from + w, Integer.class, args.toArray());
        args.add(size); args.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT l.*, s.name shop_name, k.sku_id, k.spec, p.title, p.article_number,
              (SELECT c.purchase_cost + c.extra_cost FROM sku_costs c WHERE c.sku_pk=l.sku_pk AND (c.shop_id=l.shop_id OR c.shop_id IS NULL) ORDER BY c.shop_id NULLS LAST, c.effective_from DESC LIMIT 1) cost,
              (SELECT x.lowest_price FROM competitor_price_snapshots x WHERE x.sku_pk=l.sku_pk ORDER BY x.captured_at DESC LIMIT 1) competitor_price
            """ + from + w + " ORDER BY l.id LIMIT ? OFFSET ?", args.toArray());
        return Map.of("total", total, "items", rows);
    }
    public record BatchEdit(List<Long> ids, Boolean excluded, Long minProfit, Boolean clearAnomaly) {}
    @PostMapping("/listings/batch")
    public Map<String, Object> batchEdit(@RequestBody BatchEdit b, @RequestHeader(value = "X-User", required = false) String u) {
        Long[] allowed = scope.allowedShopIds(u); Long[] ids = b.ids().toArray(new Long[0]); int n = 0;
        if (b.excluded() != null) n = jdbc.update("UPDATE shop_sku_listings SET repricing_excluded=? WHERE id = ANY(?) AND shop_id = ANY(?)", b.excluded(), ids, allowed);
        if (b.minProfit() != null) n = jdbc.update("UPDATE shop_sku_listings SET min_profit=? WHERE id = ANY(?) AND shop_id = ANY(?)", b.minProfit(), ids, allowed);
        if (Boolean.TRUE.equals(b.clearAnomaly())) n = jdbc.update("UPDATE shop_sku_listings SET anomaly_flag=NULL WHERE id = ANY(?) AND shop_id = ANY(?)", ids, allowed);
        audit.log(u, "BATCH_EDIT_LISTINGS", null, "LISTING", null, Map.of("count", ids.length));
        return Map.of("updated", n);
    }
    @PostMapping("/costs/import")
    public CostImportService.Result importCosts(@RequestParam("file") MultipartFile f, @RequestHeader(value = "X-User", required = false) String u) throws Exception {
        var r = costs.importExcel(f.getInputStream(), "excel-" + System.currentTimeMillis());
        audit.log(u, "IMPORT_COSTS", null, "COST", null, Map.of("imported", r.imported(), "errors", r.errors().size()));
        return r;
    }

    // ---------- 策略 ----------
    @GetMapping("/strategies")
    public List<Map<String, Object>> strategies() { return jdbc.queryForList("SELECT * FROM pricing_strategies ORDER BY id"); }
    @PostMapping("/strategies")
    public Map<String, Object> saveStrategy(@RequestBody Map<String, Object> s, @RequestHeader(value = "X-User", required = false) String u) {
        Object id = s.get("id");
        Object[] v = { s.get("name"), s.get("shop_id"), s.get("mode"), s.getOrDefault("offset_type", "AMOUNT"), num(s.get("offset_value"), 0),
                s.get("min_profit"), s.get("min_price"), s.get("max_price"), num(s.get("fee_rate"), 0), num(s.get("fee_min"), 0), s.get("fee_max"),
                num(s.get("fulfillment_fee"), 0), num(s.get("max_change_ratio"), 0.15), num(s.get("price_step"), 100), s.getOrDefault("enabled", true) };
        if (id == null) {
            Long nid = jdbc.queryForObject("""
                INSERT INTO pricing_strategies(name,shop_id,mode,offset_type,offset_value,min_profit,min_price,max_price,fee_rate,fee_min,fee_max,fulfillment_fee,max_change_ratio,price_step,enabled)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id""", Long.class, v);
            audit.log(u, "CREATE_STRATEGY", null, "STRATEGY", String.valueOf(nid), Map.of("name", String.valueOf(s.get("name"))));
            return Map.of("id", nid);
        }
        Object[] w = Arrays.copyOf(v, v.length + 1); w[v.length] = ((Number) id).longValue();
        jdbc.update("""
            UPDATE pricing_strategies SET name=?,shop_id=?,mode=?,offset_type=?,offset_value=?,min_profit=?,min_price=?,max_price=?,fee_rate=?,fee_min=?,fee_max=?,
              fulfillment_fee=?,max_change_ratio=?,price_step=?,enabled=?,updated_at=now() WHERE id=?""", w);
        audit.log(u, "UPDATE_STRATEGY", null, "STRATEGY", String.valueOf(id), Map.of());
        return Map.of("id", id);
    }
    private static Object num(Object o, Number d) { return o == null ? d : o; }
    public record AssignReq(long strategyId, long shopId, String scope, Long targetId, Integer priority) {}
    @PostMapping("/strategy-assignments")
    public void assign(@RequestBody AssignReq r, @RequestHeader(value = "X-User", required = false) String u) {
        scope.check(u, r.shopId());
        jdbc.update("""
            INSERT INTO strategy_assignments(strategy_id,shop_id,scope,target_id,priority) VALUES (?,?,?,?,?)
            ON CONFLICT (shop_id, scope, target_id) DO UPDATE SET strategy_id=EXCLUDED.strategy_id, priority=EXCLUDED.priority""",
                r.strategyId(), r.shopId(), r.scope(), r.targetId(), Optional.ofNullable(r.priority()).orElse(0));
        audit.log(u, "ASSIGN_STRATEGY", r.shopId(), "STRATEGY", String.valueOf(r.strategyId()), Map.of("scope", r.scope()));
    }
    @GetMapping("/strategy-assignments")
    public List<Map<String, Object>> assignments() { return jdbc.queryForList("SELECT a.*, s.name strategy_name FROM strategy_assignments a JOIN pricing_strategies s ON s.id=a.strategy_id ORDER BY a.id"); }

    // ---------- 任务 ----------
    public record TaskReq(Long shopId, List<Long> listingIds, Integer priority, Boolean dryRun, String idempotencyKey) {}
    @PostMapping("/tasks")
    public Map<String, Object> createTask(@RequestBody TaskReq r, @RequestHeader(value = "X-User", required = false) String u) {
        if (r.shopId() != null) scope.check(u, r.shopId());
        String key = Optional.ofNullable(r.idempotencyKey()).orElse("manual:" + UUID.randomUUID());
        long id = tasks.createTask(r.listingIds() == null ? "MANUAL" : "BATCH", r.shopId(), r.listingIds(),
                Optional.ofNullable(r.priority()).orElse(8), Optional.ofNullable(r.dryRun()).orElse(true), key, u);
        return Map.of("taskId", id);
    }
    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks() { return jdbc.queryForList("SELECT * FROM repricing_tasks ORDER BY id DESC LIMIT 200"); }
    @GetMapping("/tasks/{id}/items")
    public List<Map<String, Object>> taskItems(@PathVariable long id) { return jdbc.queryForList("SELECT * FROM repricing_task_items WHERE task_id=? ORDER BY id", id); }
    @PostMapping("/tasks/{id}/cancel")
    public void cancel(@PathVariable long id, @RequestHeader(value = "X-User", required = false) String u) { tasks.cancel(id, u); }

    // ---------- 历史 / 日志 ----------
    @GetMapping("/history")
    public List<Map<String, Object>> history(@RequestParam(required = false) Long listingId, @RequestHeader(value = "X-User", required = false) String u) {
        return jdbc.queryForList("SELECT h.*, s.name shop_name FROM repricing_history h JOIN shops s ON s.id=h.shop_id WHERE h.shop_id = ANY(?) AND (?::bigint IS NULL OR h.listing_id=?) ORDER BY h.id DESC LIMIT 500",
                scope.allowedShopIds(u), listingId, listingId);
    }
    @GetMapping("/api-logs")
    public List<Map<String, Object>> apiLogs() { return jdbc.queryForList("SELECT * FROM api_call_logs ORDER BY id DESC LIMIT 500"); }
    @GetMapping("/audit-logs")
    public List<Map<String, Object>> auditLogs() { return jdbc.queryForList("SELECT * FROM audit_logs ORDER BY id DESC LIMIT 500"); }

    // ---------- 全局控制：暂停/恢复/紧急停止 ----------
    @PostMapping("/control/{action}")
    public List<Map<String, Object>> control(@PathVariable String action, @RequestHeader(value = "X-User", required = false) String u) {
        switch (action) {
            case "resume" -> jdbc.update("UPDATE system_switches SET value='RUNNING', updated_at=now() WHERE name='GLOBAL_REPRICING'");
            case "pause" -> jdbc.update("UPDATE system_switches SET value='PAUSED', updated_at=now() WHERE name='GLOBAL_REPRICING'");
            case "emergency-stop" -> {
                jdbc.update("UPDATE system_switches SET value='ON', updated_at=now() WHERE name='EMERGENCY_STOP'");
                jdbc.update("UPDATE system_switches SET value='PAUSED', updated_at=now() WHERE name='GLOBAL_REPRICING'");
            }
            case "release-stop" -> jdbc.update("UPDATE system_switches SET value='OFF', updated_at=now() WHERE name='EMERGENCY_STOP'");
            default -> throw new IllegalArgumentException("未知操作");
        }
        audit.log(u, "CONTROL_" + action.toUpperCase(), null, "SYSTEM", null, Map.of());
        return jdbc.queryForList("SELECT name, value FROM system_switches");
    }

    // ---------- 模拟数据（仅 MOCK 适配器） ----------
    @PostMapping("/dev/mock-seed")
    public Map<String, Object> mockSeed(@RequestParam(defaultValue = "5") int spus, @RequestParam(defaultValue = "20") int skus) {
        mock.seed(spus, skus); return Map.of("ok", true);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Map<String, String> bad(IllegalArgumentException e) { return Map.of("message", e.getMessage()); }
}
