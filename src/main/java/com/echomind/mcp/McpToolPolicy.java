package com.echomind.mcp;

import com.echomind.config.EchoMindProperties;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MCP 工具调用策略，整体为 fail-closed（默认拒绝）：
 * <ul>
 *   <li>allowed-tools 为空 = 全部拒绝（旧实现为空 = 全放行，接入含写操作的
 *       server 而忘记配置白名单时，LLM 一次幻觉即可自动调用破坏性工具）；</li>
 *   <li>Agent 自动调用必须显式出现在 read-only-tools 中，该列表为空时自动调用一律拒绝；</li>
 *   <li>通配符支持任意位置的 {@code *} 与 {@code ?}（旧实现只支持前缀 {@code *}，
 *       运维按 glob 直觉写 {@code *secret*} 会静默失效）。</li>
 * </ul>
 */
public class McpToolPolicy {

    private final EchoMindProperties.Server server;

    public McpToolPolicy(EchoMindProperties.Server server) {
        this.server = server;
    }

    public boolean visibleToAgent(String toolName) {
        return allowedByList(toolName)
                && !matches(server.getBlockedTools(), toolName)
                && !matches(server.getDangerousTools(), toolName)
                && matches(server.getReadOnlyTools(), toolName);
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
        if (automatic && !matches(server.getReadOnlyTools(), toolName)) {
            return PolicyCheck.deny("automatic call requires read-only tool: " + toolName);
        }
        return PolicyCheck.allow();
    }

    private boolean allowedByList(String toolName) {
        //fail-closed：白名单为空即全部拒绝
        return !server.getAllowedTools().isEmpty() && matches(server.getAllowedTools(), toolName);
    }

    private boolean matches(List<String> patterns, String toolName) {
        String normalized = normalize(toolName);
        return patterns.stream()
                .map(this::normalize)
                .anyMatch(pattern -> matchesPattern(pattern, normalized));
    }

    private boolean matchesPattern(String pattern, String value) {
        if (pattern.equals("*")) {
            return true;
        }
        if (!pattern.contains("*") && !pattern.contains("?")) {
            return pattern.equals(value);
        }
        //完整 glob：* 匹配任意串、? 匹配单字符，其余字符全部按字面量转义
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return value.matches(regex.toString());
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
