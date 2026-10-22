package com.dewu.repricer.task;

import java.util.*;

public class InMemoryTaskQueue implements TaskQueue {
    private record E(long id, int pr, long nb) {}
    private final PriorityQueue<E> q = new PriorityQueue<>(Comparator.comparingInt((E e) -> -e.pr).thenComparingLong(e -> e.nb));
    public synchronized void push(long id, int pr, long nb) { q.removeIf(e -> e.id == id); q.add(new E(id, pr, nb)); }
    public synchronized Optional<Long> pollReady(long now) {
        E best = null; for (E e : q) if (e.nb <= now && (best == null || e.pr > best.pr || (e.pr == best.pr && e.nb < best.nb))) best = e;
        if (best == null) return Optional.empty(); q.remove(best); return Optional.of(best.id);
    }
    public synchronized long size() { return q.size(); }
    public synchronized void clear() { q.clear(); }
}
