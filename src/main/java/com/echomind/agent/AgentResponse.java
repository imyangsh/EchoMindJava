package com.echomind.agent;

import java.util.List;

public record AgentResponse(
        AgentType agentType,
        String content,
        boolean success,
        double confidence,
        long latencyMs,
        boolean escalate,
        List<ToolCallTrace> toolCalls,
        ExecutionStatus status,
        List<Evidence> evidence
) {
    public AgentResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        status = status == null ? (success ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED) : status;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        content = content == null ? "" : content;
    }

    public AgentResponse(AgentType agentType, String content, boolean success, double confidence,
                         long latencyMs, boolean escalate, List<ToolCallTrace> toolCalls) {
        this(agentType, content, success, confidence, latencyMs, escalate, toolCalls,
                success ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILED, List.of());
    }

    public AgentResponse(AgentType agentType, String content, boolean success, double confidence, long latencyMs, boolean escalate) {
        this(agentType, content, success, confidence, latencyMs, escalate, List.of());
    }
}
