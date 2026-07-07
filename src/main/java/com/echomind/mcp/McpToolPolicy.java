package com.echomind.mcp;

import com.echomind.config.EchoMindProperties;

import java.util.List;
import java.util.Locale;

public class McpToolPolicy {

    private final EchoMindProperties.Server server;

    public McpToolPolicy(EchoMindProperties.Server server) {
        this.server = server;
    }

    public boolean visibleToAgent(String toolName) {
        return allowedByList(toolName)
                && !matches(server.getBlockedTools(), toolName)
                && !matches(server.getDangerousTools(), toolName)
                && (server.getReadOnlyTools().isEmpty() || matches(server.getReadOnlyTools(), toolName));
    }

    public ToolPolicyStatus status(String toolName) {
        PolicyCheck automatic = canCall(toolName, true);
        PolicyCheck manual = canCall(toolName, false);
        return new ToolPolicyStatus(visibleToAgent(toolName), automatic.allowed(), automatic.reason(), manual.allowed(), manual.reason());
    }

    public PolicyCheck canCall(String toolName, boolean automatic) {
        if (!allowedByList(toolName)) {
            return PolicyCheck.deny("tool not in allowed-tools: " + toolName);
        }
        if (matches(server.getBlockedTools(), toolName)) {
            return PolicyCheck.deny("tool blocked by policy: " + toolName);
        }
        if (matches(server.getDangerousTools(), toolName)) {
            return PolicyCheck.deny("dangerous tool blocked by policy: " + toolName);
        }
        if (automatic && !server.getReadOnlyTools().isEmpty() && !matches(server.getReadOnlyTools(), toolName)) {
            return PolicyCheck.deny("automatic call requires read-only tool: " + toolName);
        }
        return PolicyCheck.allow();
    }

    private boolean allowedByList(String toolName) {
        return server.getAllowedTools().isEmpty() || matches(server.getAllowedTools(), toolName);
    }

    private boolean matches(List<String> patterns, String toolName) {
        String normalized = normalize(toolName);
        return patterns.stream()
                .map(this::normalize)
                .anyMatch(pattern -> pattern.equals("*")
                        || pattern.equals(normalized)
                        || (pattern.endsWith("*") && normalized.startsWith(pattern.substring(0, pattern.length() - 1))));
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    public record PolicyCheck(boolean allowed, String reason) {
        static PolicyCheck allow() {
            return new PolicyCheck(true, "");
        }

        static PolicyCheck deny(String reason) {
            return new PolicyCheck(false, reason == null ? "" : reason);
        }
    }

    public record ToolPolicyStatus(
            boolean agentVisible,
            boolean automaticAllowed,
            String automaticReason,
            boolean manualAllowed,
            String manualReason
    ) {
    }
}
