package com.dewu.repricer.task;

import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.quartz.QuartzJobBean;

import java.util.List;
import java.util.Map;

/**
 * Quartz 定时扫描：每次触发时检查各店铺 scan_interval_seconds，到期则按时间窗口幂等创建任务。
 * 扫描频率须以官方 API 额度和压测结果为准配置。
 */
@DisallowConcurrentExecution
public class ScheduledRepricingJob extends QuartzJobBean {
    private static final Logger log = LoggerFactory.getLogger(ScheduledRepricingJob.class);
    private final TaskService tasks; private final JdbcTemplate jdbc;
    public ScheduledRepricingJob(TaskService tasks, JdbcTemplate jdbc) { this.tasks = tasks; this.jdbc = jdbc; }

    @Override protected void executeInternal(JobExecutionContext ctx) {
        String g = jdbc.queryForObject("SELECT value FROM system_switches WHERE name='GLOBAL_REPRICING'", String.class);
        String e = jdbc.queryForObject("SELECT value FROM system_switches WHERE name='EMERGENCY_STOP'", String.class);
        if (!"RUNNING".equals(g) || "ON".equals(e)) { log.info("全局跟价未运行（{} / 紧急停止 {}），跳过定时扫描", g, e); return; }
        List<Map<String, Object>> shops = jdbc.queryForList("""
            SELECT id, scan_interval_seconds FROM shops s WHERE status='ACTIVE' AND repricing_enabled
            AND NOT EXISTS (SELECT 1 FROM repricing_tasks t WHERE t.shop_id=s.id AND t.task_type='SCHEDULED' AND t.status IN ('PENDING','RUNNING'))""");
        for (var s : shops) {
            long id = ((Number) s.get("id")).longValue(); int iv = ((Number) s.get("scan_interval_seconds")).intValue();
            tasks.createTask("SCHEDULED", id, null, 3, false, TaskService.windowKey(id, iv), "scheduler");
        }
    }
}
