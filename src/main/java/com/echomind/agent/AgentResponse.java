package com.echomind.agent;

import java.util.List;

public record AgentResponse(
        AgentType agentType,
        String content,
        boolean success,
        double confidence,
        long latencyMs,
        boolean escalate,
        List<ToolCallTrace> toolCalls
) {
    public AgentResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public AgentResponse(AgentType agentType, String content, boolean success, double confidence, long latencyMs, boolean escalate) {
        this(agentType, content, success, confidence, latencyMs, escalate, List.of());
    }
}
