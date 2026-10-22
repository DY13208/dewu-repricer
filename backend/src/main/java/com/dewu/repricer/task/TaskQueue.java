package com.dewu.repricer.task;

import java.util.Optional;

/** 任务项队列：score 越小越先执行（score = -priority*1e13 + 可执行时间戳）。 */
public interface TaskQueue {
    void push(long itemId, int priority, long notBeforeEpochMs);
    Optional<Long> pollReady(long nowEpochMs);
    long size();
    void clear();
    static double score(int priority, long notBefore) { return -priority * 1e13 + notBefore; }
}
