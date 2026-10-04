package com.echomind.tool;

import java.time.Duration;
import java.time.Instant;

public class CircuitBreaker {

    /** HALF_OPEN 状态下最多放行的并发探测数，避免恢复瞬间惊群打垮尚未恢复的下游。 */
    private static final int MAX_HALF_OPEN_PROBES = 3;

    private final int threshold;
    private final Duration recovery;
    /** 失败计数的滑动窗口：距上次失败超过该时长后计数重新开始，避免跨数小时的零星失败累计开闸。 */
    private final Duration failureWindow;
    private CircuitState state = CircuitState.CLOSED;
    private int failCount;
    private Instant openedAt;
    private Instant lastFailureAt;
    private int halfOpenProbes;

    public CircuitBreaker(int threshold, Duration recovery) {
        this(threshold, recovery, recovery);
    }

    public CircuitBreaker(int threshold, Duration recovery, Duration failureWindow) {
        this.threshold = threshold;
        this.recovery = recovery;
        this.failureWindow = failureWindow;
    }

    public synchronized boolean allow() {
        if (state == CircuitState.CLOSED) {
            return true;
        }
        if (state == CircuitState.OPEN && openedAt != null && Instant.now().isAfter(openedAt.plus(recovery))) {
            state = CircuitState.HALF_OPEN;
            halfOpenProbes = 1;
            return true;
        }
        if (state == CircuitState.HALF_OPEN && halfOpenProbes < MAX_HALF_OPEN_PROBES) {
            halfOpenProbes++;
            return true;
        }
        return false;
    }

    public synchronized void recordSuccess() {
        failCount = 0;
        state = CircuitState.CLOSED;
        openedAt = null;
        halfOpenProbes = 0;
    }

    public synchronized void recordFailure() {
        Instant now = Instant.now();
        if (lastFailureAt != null && now.isAfter(lastFailureAt.plus(failureWindow))) {
            failCount = 0;
        }
        lastFailureAt = now;
        failCount++;
        if (state == CircuitState.HALF_OPEN || failCount >= threshold) {
            state = CircuitState.OPEN;
            openedAt = now;
            halfOpenProbes = 0;
        }
    }

    public synchronized CircuitState state() {
        return state;
    }
}
