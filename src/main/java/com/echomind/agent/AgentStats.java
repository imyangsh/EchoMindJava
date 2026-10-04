package com.echomind.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class AgentStats {

    private static final int WINDOW_SIZE = 100;
    private static final long WINDOW_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(5);

    private final Deque<Outcome> recent = new ArrayDeque<>();
    private final Map<ExecutionStatus, Long> outcomes = new EnumMap<>(ExecutionStatus.class);
    private long total;
    private long success;
    private long totalLatencyMs;
    private int inFlight;
    private double monitorPenalty;

    public synchronized Attempt begin() {
        inFlight++;
        return new Attempt(this);
    }

    public synchronized void record(boolean ok, long latencyMs) {
        recordOutcome(ok ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED, latencyMs);
    }

    public synchronized void record(ExecutionStatus status, long latencyMs) {
        recordOutcome(status, latencyMs);
    }

    private synchronized void release() {
        inFlight = Math.max(0, inFlight - 1);
    }

    private void recordOutcome(ExecutionStatus status, long latencyMs) {
        long latency = Math.max(0, latencyMs);
        total++;
        if (status == ExecutionStatus.SUCCESS) success++;
        totalLatencyMs += latency;
        outcomes.merge(status, 1L, Long::sum);
        trim();
        recent.addLast(new Outcome(status, latency, System.nanoTime()));
        while (recent.size() > WINDOW_SIZE) recent.removeFirst();
    }

    private void trim() {
        long oldest = System.nanoTime() - WINDOW_NANOS;
        while (!recent.isEmpty() && recent.getFirst().completedNanos() < oldest) recent.removeFirst();
    }

    public synchronized long total() { return total; }
    public synchronized int inFlight() { return inFlight; }
    public synchronized double successRate() { return total == 0 ? 1.0 : (double) success / total; }
    public synchronized double avgLatencyMs() { return total == 0 ? 0.0 : (double) totalLatencyMs / total; }
    public synchronized long outcomeCount(ExecutionStatus status) { return outcomes.getOrDefault(status, 0L); }

    public synchronized double routingScore() {
        trim();
        long count = recent.size();
        long ok = recent.stream().filter(o -> o.status() == ExecutionStatus.SUCCESS).count();
        // A modest prior avoids giving a brand-new instance a perfect score.
        double rate = (ok + 2.0) / (count + 3.0);
        double latency = recent.stream().mapToLong(Outcome::latencyMs).average().orElse(1000.0);
        double base = rate * 0.7 + 0.3 / (1.0 + latency / 1000.0);
        return base * Math.max(0.0, 1.0 - monitorPenalty) / (1.0 + inFlight);
    }

    public synchronized Map<String, Object> snapshot() {
        trim();
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("total", total);
        result.put("success_rate", successRate());
        result.put("avg_ms", avgLatencyMs());
        result.put("monitor_penalty", monitorPenalty);
        result.put("routing_score", routingScore());
        result.put("in_flight", inFlight);
        result.put("recent_total", recent.size());
        result.put("recent_success_rate", recent.isEmpty() ? 1.0
                : (double) recent.stream().filter(o -> o.status() == ExecutionStatus.SUCCESS).count() / recent.size());
        result.put("recent_avg_ms", recent.stream().mapToLong(Outcome::latencyMs).average().orElse(0.0));
        for (ExecutionStatus status : ExecutionStatus.values()) {
            String name = status.name().toLowerCase(java.util.Locale.ROOT);
            result.put(name + "_total", outcomes.getOrDefault(status, 0L));
            result.put("recent_" + name, recent.stream().filter(o -> o.status() == status).count());
        }
        return result;
    }

    public synchronized double monitorPenalty() { return monitorPenalty; }

    public synchronized void setMonitorPenalty(double penalty) {
        monitorPenalty = Double.isFinite(penalty) ? Math.min(0.9, Math.max(0.0, penalty)) : 0.0;
    }

    public static final class Attempt {
        private final AgentStats owner;
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        private Attempt(AgentStats owner) { this.owner = owner; }

        public void complete(ExecutionStatus status, long latencyMs) {
            terminate(status, latencyMs);
            release();
        }

        /** Record the deadline outcome while a non-interruptible worker may still be active. */
        public void terminate(ExecutionStatus status, long latencyMs) {
            if (completed.compareAndSet(false, true)) owner.record(status, latencyMs);
        }

        public void release() {
            if (released.compareAndSet(false, true)) owner.release();
        }
    }

    private record Outcome(ExecutionStatus status, long latencyMs, long completedNanos) { }
}
