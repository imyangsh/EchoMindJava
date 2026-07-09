package com.echomind.monitor;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.config.EchoMindProperties;
import com.echomind.tool.KnowledgeToolManager;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class PerformanceMonitor {

    private final AgentOrchestrator orchestrator;
    private final KnowledgeToolManager toolManager;
    private final EchoMindProperties properties;
    private final List<Map<String, Object>> alerts = new ArrayList<>();
    private final List<Map<String, Object>> suggestions = new ArrayList<>();
    private final Set<String> alertKeys = new HashSet<>();
    private final Set<String> suggestionKeys = new HashSet<>();
    private final RestClient restClient = RestClient.create();
    private final Counter monitorCollections;
    private final Timer monitorCollectionTimer;
    private volatile double agentSuccessRate = 1.0;

    public PerformanceMonitor(AgentOrchestrator orchestrator, KnowledgeToolManager toolManager, EchoMindProperties properties, MeterRegistry meterRegistry) {
        this.orchestrator = orchestrator;
        this.toolManager = toolManager;
        this.properties = properties;
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
            AtomicReference<Double> minSuccessRate = new AtomicReference<>(1.0);
            //遍历每个 Agent 实例的统计数据
            agentStats.forEach((key, value) -> {
                //类型检查，因为 stats() 返回的是 Map<String, Object>，value 是 Object 类型
                if (value instanceof Map<?, ?> map) {
                    //值提取 asDouble(...)：从子 Map 里取出 success_rate 和 avg_ms，用 asDouble 转成 double
                    double sr = asDouble(map.get("success_rate"), 1.0);
                    double avg = asDouble(map.get("avg_ms"), 0.0);
                    //找到本轮所有 Agent 中成功率最低的那个，记录到类级字段 agentSuccessRate 里
                    minSuccessRate.accumulateAndGet(sr, Math::min);
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
            if (!penalties.isEmpty()) {
                addSuggestion("路由权重已根据在线表现调整", "检查 /monitor 中低成功率或高延迟 Agent，必要时优化 prompt 或增加实例。", 7);
            }
        });
    }

    public Map<String, Object> summary() {
        return Map.of(
                "agent_stats", orchestrator.stats(),
                "tool_stats", toolManager.stats(),
                "active_alerts", tail(alerts, 10),
                "suggestions", tail(suggestions, 5)
        );
    }

    private double routingPenalty(double successRate, double avgMs) {
        double penalty = 0.0;
        if (successRate < 0.90) {
            penalty += Math.min(0.5, (0.90 - successRate) * 2);
        }
        if (avgMs > 3000) {
            penalty += Math.min(0.4, (avgMs - 3000) / 10000);
        }
        return Math.min(0.9, penalty);
    }

    private void addAlert(String metric, double value, double threshold) {
        if (alertKeys.add(metric)) {
            Map<String, Object> alert = Map.of("metric", metric, "value", value, "threshold", threshold, "resolved", false);
            alerts.add(alert);
            sendWebhook(alert);
        }
    }

    private void addSuggestion(String title, String action, int priority) {
        if (suggestionKeys.add(title)) {
            suggestions.add(Map.of("title", title, "action", action, "priority", priority));
        }
    }

    private void sendWebhook(Map<String, Object> alert) {
        String url = properties.getMonitor().getWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            restClient.post().uri(url).body(alert).retrieve().toBodilessEntity();
        } catch (Exception ignored) {
        }
    }

    private double asDouble(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private List<Map<String, Object>> tail(List<Map<String, Object>> values, int n) {
        return values.subList(Math.max(0, values.size() - n), values.size());
    }
}
