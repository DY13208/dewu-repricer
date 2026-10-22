package com.dewu.repricer.config;

import com.dewu.repricer.task.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class QueueConfig {
    @Bean
    @ConditionalOnProperty(name = "repricer.queue", havingValue = "memory")
    TaskQueue memoryQueue() { return new InMemoryTaskQueue(); }

    @Bean
    @ConditionalOnProperty(name = "repricer.queue", havingValue = "redis", matchIfMissing = true)
    TaskQueue redisQueue(StringRedisTemplate t) { return new RedisTaskQueue(t); }
}
