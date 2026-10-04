package com.echomind.tool;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 线程安全的工具调用统计。
 * 旧实现只在 record() 上加了 synchronized，读方法无同步且字段非 volatile，
 * 监控调度线程可能永远读到陈旧值；改用并发累加器后读写均无锁且始终可见。
 */
public class ToolStats {

    private final LongAdder total = new LongAdder();
    private final LongAdder success = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final AtomicInteger consecutiveFails = new AtomicInteger();
    private final LongAdder degraded = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();

    public void record(boolean ok, long latencyMs) {
        record(ok, latencyMs, false, false);
    }

    public void record(boolean ok, long latencyMs, boolean isDegraded, boolean cached) {
        total.increment();
        totalLatencyMs.addAndGet(Math.max(0, latencyMs));
        if (isDegraded) degraded.increment();
        if (cached) cacheHits.increment();
        if (ok) {
            success.increment();
            consecutiveFails.set(0);
        } else {
            failed.increment();
            consecutiveFails.incrementAndGet();
        }
    }

    public long total() {
        return total.sum();
    }

    public double successRate() {
        long totalCount = total.sum();
        return totalCount == 0 ? 1.0 : (double) success.sum() / totalCount;
    }

    public double avgLatencyMs() {
        long totalCount = total.sum();
        return totalCount == 0 ? 0.0 : (double) totalLatencyMs.get() / totalCount;
    }

    public int consecutiveFails() {
        return consecutiveFails.get();
    }

    public long degradedCalls() { return degraded.sum(); }

    public long cacheHits() { return cacheHits.sum(); }
}
