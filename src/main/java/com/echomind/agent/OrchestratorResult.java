package com.echomind.agent;

import com.echomind.intent.IntentCategory;

import java.util.List;

public record OrchestratorResult(
        String requestId,
        String response,
        AgentType agentType,
        IntentCategory intent,
        boolean escalated,
        long latencyMs,
        List<ToolCallTrace> toolCalls
) {
    public OrchestratorResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public OrchestratorResult(String requestId, String response, AgentType agentType, IntentCategory intent, boolean escalated, long latencyMs) {
        this(requestId, response, agentType, intent, escalated, latencyMs, List.of());
    }
}
