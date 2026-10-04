package com.echomind.monitor;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AnswerVerifier.VerificationStatus;
import com.echomind.agent.ExecutionMetrics;
import com.echomind.agent.ExecutionStatus;
import com.echomind.config.EchoMindProperties;
import com.echomind.tool.KnowledgeToolManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PerformanceMonitor {

    private static final Logger log = LoggerFactory.getLogger(PerformanceMonitor.class);
    /** 同一指标的告警冷却时间：冷却期后允许再次触发（旧实现同一指标终生只告警一次）。 */
    private static final Duration ALERT_COOLDOWN = Duration.ofMinutes(10);
    private static final int MAX_ALERTS = 100;
    private static final int MAX_SUGGESTIONS = 50;

    private final AgentOrchestrator orchestrator;
    private final KnowledgeToolManager toolManager;
    private final EchoMindProperties properties;
    //调度线程写、HTTP 线程读，必须用并发容器（旧实现的 ArrayList/HashSet 会抛 CME）
    private final List<Map<String, Object>> alerts = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> suggestions = new CopyOnWriteArrayList<>();
    private final Map<String, Instant> alertLastFired = new ConcurrentHashMap<>();
    private final Set<String> suggestionKeys = ConcurrentHashMap.newKeySet();
    private final RestClient restClient;
    private final Counter monitorCollections;
    private final Timer monitorCollectionTimer;
    private final MeterRegistry meterRegistry;
    private final ConcurrentLinkedDeque<QualityObservation> qualityWindow = new ConcurrentLinkedDeque<>();
    private static final Duration QUALITY_WINDOW = Duration.ofMinutes(5);
    private static final int MAX_QUALITY_OBSERVATIONS = 1000;
    private static final int MIN_ROUTING_SAMPLES = 20;
    private volatile double agentSuccessRate = Double.NaN;

    public PerformanceMonitor(AgentOrchestrator orchestrator, KnowledgeToolManager toolManager, EchoMindProperties properties, MeterRegistry meterRegistry) {
        this.orchestrator = orchestrator;
        this.toolManager = toolManager;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        //webhook 请求必须有超时：默认的 RestClient 读超时是无限，
        //目标端挂起会把整个调度线程冻住，监控采集永久停摆。
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        Gauge.builder("echomind_agent_success_rate", () -> agentSuccessRate).register(meterRegistry);
        this.monitorCollections = Counter.builder("echomind_monitor_collections_total").register(meterRegistry);
        this.monitorCollectionTimer = Timer.builder("echomind_monitor_collection_duration").register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        collect();
    }

    /*
    collect() 是一个由 @Scheduled 驱动的定时任务，每 10 秒自动运行一次
    从编排器拉取所有 Agent 的运行统计，依据成功率与延迟计算惩罚值，再回写更新路由权重，同时触发告警和优化建议。
     */
    @Scheduled(fixedDelay = 10000)
    public void collect() {
        monitorCollectionTimer.record(() -> {
            monitorCollections.increment(); //计数器
            //拉取 Agent 统计数据
            Map<String, Object> agentStats = orchestrator.stats();
            //penalties 用来收集每个 Agent 的惩罚值
            Map<String, Double> penalties = new HashMap<>();
            //minSuccessRate 初始化为 1.0（满分成功率），意图是追踪本轮所有 Agent 中的最低成功率
            AtomicReference<Double> minSuccessRate = new AtomicReference<>(Double.NaN);
            //遍历每个 Agent 实例的统计数据
            agentStats.forEach((key, value) -> {
                //类型检查，因为 stats() 返回的是 Map<String, Object>，value 是 Object 类型
                if (value instanceof Map<?, ?> map) {
                    //值提取 asDouble(...)：从子 Map 里取出 success_rate 和 avg_ms，用 asDouble 转成 double
                    double samples = asDouble(map.get("recent_total"), asDouble(map.get("total"), 0.0));
                    double sr = asDouble(map.get("recent_success_rate"), asDouble(map.get("success_rate"), 1.0));
                    double avg = asDouble(map.get("recent_avg_ms"), asDouble(map.get("avg_ms"), 0.0));
                    //找到本轮所有 Agent 中成功率最低的那个，记录到类级字段 agentSuccessRate 里
                    if (samples > 0) minSuccessRate.accumulateAndGet(sr, (previous, current) ->
                            Double.isNaN(previous) ? current : Math.min(previous, current));
                    if (samples < MIN_ROUTING_SAMPLES) {
                        penalties.put(key, 0.0);
                        return;
                    }
                    //成功率过低
                    if (sr < properties.getMonitor().getSuccessRateThreshold()) {
                        addAlert("agent_success_rate:" + key, sr, properties.getMonitor().getSuccessRateThreshold());
                    }
                    //延迟过高
                    if (avg > properties.getMonitor().getLatencyMsThreshold()) {
                        addAlert("agent_avg_ms:" + key, avg, properties.getMonitor().getLatencyMsThreshold());
                    }
                    //routingPenalty(sr, avg) 根据成功率和延迟算出一个惩罚值，塞进 penalties Map
                    penalties.put(key, routingPenalty(sr, avg));
                }
            });
            agentSuccessRate = minSuccessRate.get();
            //把计算好的惩罚值 Map 回写给编排器
            orchestrator.updateRoutingPenalties(penalties);
            //如果本轮有 Agent 被施加了惩罚（penalties 非空），就添加一条优化建议
            if (penalties.values().stream().anyMatch(value -> value > 0)) {
                addSuggestion("路由权重已根据在线表现调整", "检查 /monitor 中低成功率或高延迟 Agent，必要时优化 prompt 或增加实例。", 7);
            }
        });
    }

    public Map<String, Object> summary() {
        return Map.of(
                "agent_stats", orchestrator.stats(),
                "tool_stats", toolManager.stats(),
                "quality_stats", qualitySummary(),
                "active_alerts", tail(alerts, 10),
                "suggestions", tail(suggestions, 5)
        );
    }

    private double routingPenalty(double successRate, double avgMs) {
        double penalty = 0.0;
        double successThreshold = properties.getMonitor().getSuccessRateThreshold();
        double latencyThreshold = properties.getMonitor().getLatencyMsThreshold();
        if (successRate < successThreshold) {
            penalty += Math.min(0.5, (successThreshold - successRate) * 2);
        }
        if (avgMs > latencyThreshold) {
            penalty += Math.min(0.4, (avgMs - latencyThreshold) / Math.max(1, latencyThreshold * 3));
        }
        return Math.min(0.9, penalty);
    }

    /** Answer quality is deliberately not fed back into execution routing weights. */
    public void recordQuality(ExecutionStatus executionStatus, VerificationStatus verificationStatus) {
        recordQuality(executionStatus, verificationStatus, null);
    }

    public void recordQuality(ExecutionStatus executionStatus, VerificationStatus verificationStatus, ExecutionMetrics metrics) {
        qualityWindow.addLast(new QualityObservation(Instant.now(), executionStatus, verificationStatus));
        trimQualityWindow();
        Counter.builder("echomind_chat_outcomes_total").tag("execution", executionStatus.name().toLowerCase())
                .tag("verification", verificationStatus.name().toLowerCase()).register(meterRegistry).increment();
        if (metrics != null) {
            Timer.builder("echomind_chat_duration").tag("outcome", executionStatus.name().toLowerCase())
                    .publishPercentileHistogram().register(meterRegistry).record(Duration.ofMillis(metrics.elapsedMs()));
            Counter.builder("echomind_chat_llm_calls_total").register(meterRegistry).increment(metrics.llmCalls());
            metrics.stages().forEach(stage -> Timer.builder("echomind_stage_duration")
                    .tag("stage", stageCategory(stage.stage())).tag("outcome", stage.outcome())
                    .publishPercentileHistogram().register(meterRegistry).record(Duration.ofMillis(stage.latencyMs())));
        }
    }

    private String stageCategory(String stage) {
        for (String known : List.of("memory", "retrieval", "intent", "skill", "answer", "tool", "llm", "aggregate", "repair", "verification")) {
            if (stage.startsWith(known)) return known;
        }
        return "other";
    }

    private Map<String, Object> qualitySummary() {
        trimQualityWindow();
        List<QualityObservation> observations = List.copyOf(qualityWindow);
        Map<String, Long> executions = new HashMap<>();
        Map<String, Long> verifications = new HashMap<>();
        observations.forEach(item -> {
            executions.merge(item.execution().name().toLowerCase(), 1L, Long::sum);
            verifications.merge(item.verification().name().toLowerCase(), 1L, Long::sum);
        });
        long evaluated = observations.stream().filter(item -> item.verification() == VerificationStatus.PASS
                || item.verification() == VerificationStatus.FAIL).count();
        Map<String, Object> summary = new HashMap<>();
        summary.put("window_seconds", QUALITY_WINDOW.toSeconds());
        summary.put("samples", observations.size());
        summary.put("execution_outcomes", executions);
        summary.put("verification_outcomes", verifications);
        summary.put("verification_pass_rate", evaluated == 0 ? null : (double) verifications.getOrDefault("pass", 0L) / evaluated);
        summary.put("routing_min_samples", MIN_ROUTING_SAMPLES);
        return summary;
    }

    private void trimQualityWindow() {
        Instant cutoff = Instant.now().minus(QUALITY_WINDOW);
        QualityObservation first;
        while ((first = qualityWindow.peekFirst()) != null
                && (first.at().isBefore(cutoff) || qualityWindow.size() > MAX_QUALITY_OBSERVATIONS)) qualityWindow.pollFirst();
    }

    private record QualityObservation(Instant at, ExecutionStatus execution, VerificationStatus verification) {}

    private void addAlert(String metric, double value, double threshold) {
        Instant now = Instant.now();
        Instant lastFired = alertLastFired.get(metric);
        //冷却期内不重复告警；冷却期后同一指标可以再次触发
        if (lastFired != null && now.isBefore(lastFired.plus(ALERT_COOLDOWN))) {
            return;
        }
        alertLastFired.put(metric, now);
        Map<String, Object> alert = Map.of(
                "metric", metric,
                "value", value,
                "threshold", threshold,
                "fired_at", now.toString());
        alerts.add(alert);
        while (alerts.size() > MAX_ALERTS) {
            alerts.remove(0);
        }
        sendWebhook(alert);
    }

    private void addSuggestion(String title, String action, int priority) {
        if (suggestionKeys.add(title)) {
            suggestions.add(Map.of("title", title, "action", action, "priority", priority));
            while (suggestions.size() > MAX_SUGGESTIONS) {
                suggestions.remove(0);
            }
        }
    }

    private void sendWebhook(Map<String, Object> alert) {
        String url = properties.getMonitor().getWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            restClient.post().uri(url).body(alert).retrieve().toBodilessEntity();
        } catch (Exception ex) {
            //告警投递失败本身也要可见（旧实现完全吞掉）
            log.warn("Failed to deliver alert webhook to {}: {}", url, ex.getMessage());
        }
    }

    private double asDouble(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private List<Map<String, Object>> tail(List<Map<String, Object>> values, int n) {
        //返回拷贝而非 subList 视图：视图在 Jackson 序列化期间被并发写会抛 CME
        List<Map<String, Object>> snapshot = List.copyOf(values);
        return snapshot.subList(Math.max(0, snapshot.size() - n), snapshot.size());
    }
}
