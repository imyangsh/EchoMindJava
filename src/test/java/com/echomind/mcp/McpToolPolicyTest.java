package com.echomind.mcp;

import com.echomind.config.EchoMindProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolPolicyTest {

    @Test
    void automaticCallsOnlySeeAllowedReadOnlyNonDangerousTools() {
        EchoMindProperties.Server server = new EchoMindProperties.Server();
        server.setAllowedTools(List.of("search", "read_*", "delete_file"));
        server.setReadOnlyTools(List.of("search", "read_file"));
        server.setDangerousTools(List.of("delete_file"));
        server.setBlockedTools(List.of("read_secret"));

        McpToolPolicy policy = new McpToolPolicy(server);

        assertThat(policy.visibleToAgent("search")).isTrue();
        assertThat(policy.visibleToAgent("read_file")).isTrue();
        assertThat(policy.visibleToAgent("delete_file")).isFalse();
        assertThat(policy.visibleToAgent("read_secret")).isFalse();
        assertThat(policy.visibleToAgent("write_file")).isFalse();
        assertThat(policy.canCall("search", true).allowed()).isTrue();
        assertThat(policy.canCall("delete_file", false).allowed()).isFalse();
        assertThat(policy.canCall("write_file", false).reason()).contains("allowed-tools");

        McpToolPolicy.ToolPolicyStatus status = policy.status("read_order");
        assertThat(status.agentVisible()).isFalse();
        assertThat(status.automaticAllowed()).isFalse();
        assertThat(status.automaticReason()).contains("read-only");
        assertThat(status.manualAllowed()).isTrue();
    }
}
