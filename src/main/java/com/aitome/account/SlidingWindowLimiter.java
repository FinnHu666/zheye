package com.aitome.account;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内滑动窗口计数（单实例基线；重启清零，多实例不共享）。只作为附加防护：
 * 发送间隔与小时上限以数据库中的令牌记录为准。
 */
@Component
public class SlidingWindowLimiter {
    private static final int MAX_KEYS = 50_000;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public SlidingWindowLimiter(Clock clock) { this.clock = clock; }

    /** 窗口内未超过 limit 次则记一次并返回 true，否则返回 false（不记录）。 */
    public boolean tryAcquire(String key, int limit, Duration window) {
        Deque<Long> deque = deque(key);
        synchronized (deque) {
            prune(deque, window);
            if (deque.size() >= limit) return false;
            deque.addLast(clock.millis());
            return true;
        }
    }

    public int count(String key, Duration window) {
        Deque<Long> deque = hits.get(key);
        if (deque == null) return 0;
        synchronized (deque) { prune(deque, window); return deque.size(); }
    }

    public void record(String key) {
        Deque<Long> deque = deque(key);
        synchronized (deque) { deque.addLast(clock.millis()); }
    }

    public void clear(String key) { hits.remove(key); }

    private Deque<Long> deque(String key) {
        if (hits.size() > MAX_KEYS) hits.clear(); // 极端情况下的内存保护，宁可放宽也不无限增长
        return hits.computeIfAbsent(key, k -> new ArrayDeque<>());
    }

    private void prune(Deque<Long> deque, Duration window) {
        long cutoff = clock.millis() - window.toMillis();
        while (!deque.isEmpty() && deque.peekFirst() <= cutoff) deque.removeFirst();
    }
}
