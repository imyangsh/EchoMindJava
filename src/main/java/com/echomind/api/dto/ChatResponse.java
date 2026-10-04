package com.echomind.api.dto;

import com.echomind.agent.ToolCallTrace;
import com.echomind.agent.BranchResult;
import com.echomind.agent.Evidence;
import com.echomind.agent.ExecutionMetrics;
import com.echomind.agent.ExecutionStatus;
import com.echomind.agent.AnswerVerifier.VerificationStatus;
import com.echomind.skill.SkillSelectionTrace;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

@Schema(description = "Chat response")
public record ChatResponse(
        @Schema(description = "Conversation ID", example = "conv-001")
        String conversationId,
        @Schema(description = "Assistant response")
        String response,
        @Schema(description = "Detected intent", example = "billing")
        String intent,
        @Schema(description = "Intent recognition confidence in [0,1]; 0 when abstained", example = "0.87")
        double intentConfidence,
        @Schema(description = "Channel that decided the intent: llm / semantic / pattern / abstain", example = "llm")
        String intentSource,
        @Schema(description = "Typed entities extracted from the message, e.g. order_id / error_code / amount")
        Map<String, List<String>> intentEntities,
        @Schema(description = "True when no channel produced sufficient evidence and the reply is a clarification question")
        boolean intentAbstained,
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
        long toolLatencyMs,
        String requestId,
        ExecutionStatus status,
        List<String> participants,
        List<BranchResult> branches,
        List<Evidence> evidence,
        VerificationStatus verificationStatus,
        ExecutionMetrics metrics,
        List<String> secondaryIntents,
        @Schema(description = "Fixed skill catalog version used throughout this request")
        long skillSnapshotVersion,
        @Schema(description = "Skill selection diagnostics by agent branch")
        List<SkillSelectionTrace> skillSelections
) {
    /** Retained for callers using the response shape before skill diagnostics were added. */
    public ChatResponse(String conversationId, String response, String intent, double intentConfidence,
                        String intentSource, Map<String, List<String>> intentEntities, boolean intentAbstained,
                        String agentType, boolean escalated, long latencyMs, boolean knowledgeUsed,
                        List<KnowledgeSource> knowledgeSources, boolean knowledgeSufficient, String knowledgeWarning,
                        boolean verified, boolean grounded, String verificationReason, boolean mcpToolsUsed,
                        List<ToolCallTrace> toolCalls, List<String> toolErrors, long toolLatencyMs,
                        String requestId, ExecutionStatus status, List<String> participants, List<BranchResult> branches,
                        List<Evidence> evidence, VerificationStatus verificationStatus, ExecutionMetrics metrics,
                        List<String> secondaryIntents) {
        this(conversationId, response, intent, intentConfidence, intentSource, intentEntities, intentAbstained,
                agentType, escalated, latencyMs, knowledgeUsed, knowledgeSources, knowledgeSufficient, knowledgeWarning,
                verified, grounded, verificationReason, mcpToolsUsed, toolCalls, toolErrors, toolLatencyMs,
                requestId, status, participants, branches, evidence, verificationStatus, metrics, secondaryIntents,
                0, List.of());
    }

    public ChatResponse(String conversationId, String response, String intent, double intentConfidence,
                        String intentSource, Map<String, List<String>> intentEntities, boolean intentAbstained,
                        String agentType, boolean escalated, long latencyMs, boolean knowledgeUsed,
                        List<KnowledgeSource> knowledgeSources, boolean knowledgeSufficient, String knowledgeWarning,
                        boolean verified, boolean grounded, String verificationReason, boolean mcpToolsUsed,
                        List<ToolCallTrace> toolCalls, List<String> toolErrors, long toolLatencyMs) {
        this(conversationId, response, intent, intentConfidence, intentSource, intentEntities, intentAbstained,
                agentType, escalated, latencyMs, knowledgeUsed, knowledgeSources, knowledgeSufficient, knowledgeWarning,
                verified, grounded, verificationReason, mcpToolsUsed, toolCalls, toolErrors, toolLatencyMs,
                "", intentAbstained ? ExecutionStatus.CLARIFICATION : ExecutionStatus.SUCCESS,
                agentType == null ? List.of() : List.of(agentType), List.of(), List.of(),
                verified ? VerificationStatus.PASS : VerificationStatus.UNAVAILABLE, ExecutionMetrics.empty(), List.of());
    }

    public ChatResponse {
        knowledgeSources = knowledgeSources == null ? List.of() : List.copyOf(knowledgeSources);
        knowledgeWarning = knowledgeWarning == null ? "" : knowledgeWarning;
        verificationReason = verificationReason == null ? "" : verificationReason;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolErrors = toolErrors == null ? List.of() : List.copyOf(toolErrors);
        intentSource = intentSource == null ? "" : intentSource;
        intentEntities = intentEntities == null ? Map.of() : Map.copyOf(intentEntities);
        requestId = requestId == null ? "" : requestId;
        status = status == null ? ExecutionStatus.SUCCESS : status;
        participants = participants == null ? List.of() : List.copyOf(participants);
        branches = branches == null ? List.of() : List.copyOf(branches);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        verificationStatus = verificationStatus == null ? VerificationStatus.UNAVAILABLE : verificationStatus;
        metrics = metrics == null ? ExecutionMetrics.empty() : metrics;
        secondaryIntents = secondaryIntents == null ? List.of() : List.copyOf(secondaryIntents);
        skillSelections = skillSelections == null ? List.of() : List.copyOf(skillSelections);
    }
}
