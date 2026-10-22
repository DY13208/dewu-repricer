package com.dewu.repricer.task;

import com.dewu.repricer.repo.AuditService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class TaskService {
    private final JdbcTemplate jdbc; private final TaskQueue queue; private final AuditService audit;
    public TaskService(JdbcTemplate jdbc, TaskQueue queue, AuditService audit) { this.jdbc = jdbc; this.queue = queue; this.audit = audit; }

    /**
     * 创建调价任务。idempotencyKey 相同则返回已有任务（例如定时任务按 “店铺+时间窗口” 生成键，避免重复调度）。
     * listingIds 为空表示该店铺全部在售且未排除的出价。
     */
    @Transactional
    public long createTask(String type, Long shopId, List<Long> listingIds, int priority, boolean dryRun, String idemKey, String actor) {
        List<Long> existing = jdbc.queryForList("SELECT id FROM repricing_tasks WHERE idempotency_key=?", Long.class, idemKey);
        if (!existing.isEmpty()) return existing.get(0);
        Long taskId = jdbc.queryForObject("INSERT INTO repricing_tasks(task_type,shop_id,priority,dry_run,idempotency_key,created_by,status,started_at) VALUES (?,?,?,?,?,?, 'RUNNING', now()) RETURNING id",
                Long.class, type, shopId, priority, dryRun, idemKey, actor);
        String where = listingIds == null || listingIds.isEmpty()
                ? "l.shop_id=? AND l.status='ON_SALE' AND NOT l.repricing_excluded"
                : "l.id = ANY (?)";
        Object arg = listingIds == null || listingIds.isEmpty() ? shopId : listingIds.toArray(new Long[0]);
        List<Long> items = jdbc.queryForList("""
            INSERT INTO repricing_task_items(task_id,shop_id,listing_id,idempotency_key)
            SELECT ?, l.shop_id, l.id, ? || ':' || l.id FROM shop_sku_listings l WHERE %s RETURNING id""".formatted(where),
                Long.class, taskId, "t" + taskId, arg);
        jdbc.update("UPDATE repricing_tasks SET total=? WHERE id=?", items.size(), taskId);
        long now = System.currentTimeMillis();
        for (Long id : items) queue.push(id, priority, now);
        audit.log(actor, "CREATE_TASK", shopId, "TASK", String.valueOf(taskId), Map.of("type", type, "items", items.size(), "dryRun", dryRun));
        return taskId;
    }
    public static String windowKey(long shopId, int intervalSec) {
        long w = System.currentTimeMillis() / 1000 / Math.max(60, intervalSec);
        return "sched:" + shopId + ":" + w;
    }
    @Transactional
    public void cancel(long taskId, String actor) {
        jdbc.update("UPDATE repricing_tasks SET status='CANCELLED', finished_at=now() WHERE id=?", taskId);
        jdbc.update("UPDATE repricing_task_items SET status='SKIPPED', decision_reason='任务已取消' WHERE task_id=? AND status IN ('PENDING','RETRY')", taskId);
        audit.log(actor, "CANCEL_TASK", null, "TASK", String.valueOf(taskId), Map.of());
    }
    /** 服务重启后的错误恢复：把未完成项重新入队。 */
    public int recover() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT i.id, t.priority FROM repricing_task_items i JOIN repricing_tasks t ON t.id=i.task_id
            WHERE i.status IN ('PENDING','RETRY') AND t.status<>'CANCELLED'""");
        long now = System.currentTimeMillis();
        rows.forEach(r -> queue.push(((Number) r.get("id")).longValue(), ((Number) r.get("priority")).intValue(), now));
        return rows.size();
    }
    static String ts() { return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")); }
}
