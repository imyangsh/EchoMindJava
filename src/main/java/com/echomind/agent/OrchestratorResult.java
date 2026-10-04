package com.echomind.agent;

import com.echomind.intent.IntentCategory;

import java.util.List;
import java.util.Map;

public record OrchestratorResult(
        String requestId,
        String response,
        AgentType agentType,
        IntentCategory intent,
        boolean escalated,
        long latencyMs,
        List<ToolCallTrace> toolCalls,
        /** 意图识别置信度，用于线上排查"为什么走了这个 Agent"。 */
        double intentConfidence,
        /** 定夺意图的通道：llm / semantic / pattern / abstain。 */
        String intentSource,
        /** 意图识别抽出的类型化实体，随响应一并回传。 */
        Map<String, List<String>> entities,
        /** 意图拒识（三通道无证据）时为 true，此时未调用任何 Agent，响应就是澄清话术。 */
        boolean intentAbstained,
        /** 次意图：一句话同时涉及多个领域时的其余候选。 */
        List<IntentCategory> secondaryIntents,
        ExecutionStatus status,
        List<BranchResult> branches,
        List<Evidence> evidence,
        ExecutionMetrics metrics
) {
    public OrchestratorResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        entities = entities == null ? Map.of() : Map.copyOf(entities);
        intentSource = intentSource == null ? "" : intentSource;
        secondaryIntents = secondaryIntents == null ? List.of() : List.copyOf(secondaryIntents);
        status = status == null ? (intentAbstained ? ExecutionStatus.CLARIFICATION : ExecutionStatus.SUCCESS) : status;
        branches = branches == null ? List.of() : List.copyOf(branches);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        metrics = metrics == null ? ExecutionMetrics.empty() : metrics;
    }

    public OrchestratorResult(String requestId, String response, AgentType agentType, IntentCategory intent,
                              boolean escalated, long latencyMs, List<ToolCallTrace> toolCalls,
                              double intentConfidence, String intentSource, Map<String, List<String>> entities,
                              boolean intentAbstained, List<IntentCategory> secondaryIntents) {
        this(requestId, response, agentType, intent, escalated, latencyMs, toolCalls, intentConfidence,
                intentSource, entities, intentAbstained, secondaryIntents,
                intentAbstained ? ExecutionStatus.CLARIFICATION : ExecutionStatus.SUCCESS,
                List.of(), List.of(), null);
    }

    public OrchestratorResult(String requestId, String response, AgentType agentType, IntentCategory intent, boolean escalated, long latencyMs) {
        this(requestId, response, agentType, intent, escalated, latencyMs, List.of(), 0.0, "", Map.of(), false, List.of());
    }
}
