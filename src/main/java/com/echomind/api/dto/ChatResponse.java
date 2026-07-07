package com.echomind.api.dto;

import com.echomind.agent.ToolCallTrace;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Chat response")
public record ChatResponse(
        @Schema(description = "Conversation ID", example = "conv-001")
        String conversationId,
        @Schema(description = "Assistant response")
        String response,
        @Schema(description = "Detected intent", example = "billing")
        String intent,
        @Schema(description = "Agent type that handled the request", example = "billing")
        String agentType,
        @Schema(description = "Whether human escalation is needed")
        boolean escalated,
        @Schema(description = "Processing latency in milliseconds", example = "253")
        long latencyMs,
        @Schema(description = "Whether knowledge retrieval was used")
        boolean knowledgeUsed,
        @Schema(description = "Knowledge sources used as answer evidence")
        List<KnowledgeSource> knowledgeSources,
        @Schema(description = "Whether retrieved knowledge is sufficient for a grounded answer")
        boolean knowledgeSufficient,
        @Schema(description = "Knowledge warning when retrieval has weak or missing evidence")
        String knowledgeWarning,
        @Schema(description = "Whether answer verification passed")
        boolean verified,
        @Schema(description = "Whether the answer is grounded in context")
        boolean grounded,
        @Schema(description = "Answer verification reason")
        String verificationReason,
        @Schema(description = "Whether MCP tools were automatically called")
        boolean mcpToolsUsed,
        @Schema(description = "MCP tool call trace")
        List<ToolCallTrace> toolCalls,
        @Schema(description = "MCP tool call errors")
        List<String> toolErrors,
        @Schema(description = "Total MCP tool latency in milliseconds")
        long toolLatencyMs
) {
    public ChatResponse {
        knowledgeSources = knowledgeSources == null ? List.of() : List.copyOf(knowledgeSources);
        knowledgeWarning = knowledgeWarning == null ? "" : knowledgeWarning;
        verificationReason = verificationReason == null ? "" : verificationReason;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolErrors = toolErrors == null ? List.of() : List.copyOf(toolErrors);
    }
}
