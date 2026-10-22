package com.dewu.repricer.task;

import com.dewu.repricer.adapter.AdapterRegistry;
import com.dewu.repricer.config.RepricerProperties;
import com.dewu.repricer.risk.ShopCircuitBreaker;
import com.dewu.repricer.risk.TokenBucketRateLimiter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/** 后台消费者：从队列取任务项交给执行器（虚拟线程，并发度受各店铺限流约束）。 */
@Component
public class TaskWorker {
    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
    private final TokenBucketRateLimiter limiter; private final TaskQueue queue; private final RepricingExecutor executor; private final TaskService tasks; private final JdbcTemplate jdbc;
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore concurrency = new Semaphore(32);
    private volatile boolean running = true;

    public TaskWorker(TaskQueue queue, com.dewu.repricer.repo.JdbcRepricingStore store, AdapterRegistry adapters, TokenBucketRateLimiter limiter,
                      ShopCircuitBreaker breaker, RepricerProperties p, TaskService tasks, JdbcTemplate jdbc, MeterRegistry metrics) {
        this.limiter = limiter; this.queue = queue; this.tasks = tasks; this.jdbc = jdbc;
        this.executor = new RepricingExecutor(store, adapters, queue, limiter, breaker, p.dryRun(), p.maxAttempts(), p.backoffBaseMs(), p.backoffMaxMs(), metrics);
        Gauge.builder("repricer.queue.size", queue, TaskQueue::size).register(metrics);
        if (p.dryRun()) log.warn("系统运行于全局 DRY-RUN 模式：只计算目标价，不调用任何改价接口");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        jdbc.query("SELECT id, qps_limit FROM shops", rs -> { limiter.configure(rs.getLong(1), rs.getDouble(2)); });
        log.info("恢复未完成任务项 {} 条", tasks.recover());
        Thread.ofVirtual().name("repricer-dispatcher").start(this::loop);
    }
    private void loop() {
        while (running) {
            try {
                var id = queue.pollReady(System.currentTimeMillis());
                if (id.isEmpty()) { Thread.sleep(200); continue; }
                concurrency.acquire();
                pool.submit(() -> { try { executor.execute(id.get()); } catch (Exception e) { log.error("执行失败 item={}", id.get(), e); } finally { concurrency.release(); } });
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch (Exception e) { log.error("调度循环异常", e); try { Thread.sleep(1000); } catch (InterruptedException ie) { return; } }
        }
    }
    @PreDestroy public void stop() { running = false; pool.shutdown(); }
}
