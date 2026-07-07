package com.echomind.mcp;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StdioMcpClientIntegrationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void talksToRealContentLengthStdioProcessForListAndCall() {
        McpClientManager manager = new McpClientManager(propertiesFor(false), objectMapper);
        try {
            manager.reload();

            List<McpToolInfo> agentTools = manager.listTools();
            assertThat(agentTools).extracting(McpToolInfo::name).containsExactly("echo_order");

            List<Map<String, Object>> summaries = manager.listAllToolSummaries();
            assertThat(summaries).hasSize(2);
            assertThat(summaries)
                    .filteredOn(summary -> summary.get("name").equals("delete_order"))
                    .singleElement()
                    .extracting(summary -> (Map<?, ?>) summary.get("policy"))
                    .satisfies(policy -> {
                        assertThat(policy.get("agent_visible")).isEqualTo(false);
                        assertThat(policy.get("manual_allowed")).isEqualTo(false);
                    });

            McpCallResult result = manager.callTool("fake", "echo_order", Map.of("order_id", "A-100", "mode", "brief"));
            assertThat(result.success()).isTrue();
            assertThat(result.result().path("content").get(0).path("text").asText()).isEqualTo("echo_order:A-100");
            assertThat(result.result().path("toolsListCalls").asInt()).isEqualTo(1);

            McpCallResult invalid = manager.callTool("fake", "echo_order", Map.of("order_id", 100));
            assertThat(invalid.success()).isFalse();
            assertThat(invalid.error()).contains("invalid tool arguments").contains("$.order_id must be string");
        } finally {
            manager.destroy();
        }
    }

    @Test
    void supportsLegacyJsonLineStdioProcess() {
        McpClientManager manager = new McpClientManager(propertiesFor(true), objectMapper);
        try {
            manager.reload();

            McpCallResult result = manager.callTool("fake", "echo_order", Map.of("order_id", "A-200", "mode", "full"));
            assertThat(result.success()).isTrue();
            assertThat(result.result().path("content").get(0).path("text").asText()).isEqualTo("echo_order:A-200");
        } finally {
            manager.destroy();
        }
    }

    private EchoMindProperties propertiesFor(boolean jsonLines) {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().setRequestTimeoutMs(5000);

        EchoMindProperties.Server server = new EchoMindProperties.Server();
        server.setCommand(javaCommand());
        List<String> args = new java.util.ArrayList<>(List.of(
                "-cp",
                System.getProperty("java.class.path"),
                FakeStdioMcpServer.class.getName()
        ));
        if (jsonLines) {
            args.add("--json-lines");
            server.setStdioFormat("json-lines");
        }
        server.setArgs(args);
        server.setAllowedTools(List.of("echo_order", "delete_order"));
        server.setReadOnlyTools(List.of("echo_order"));
        server.setDangerousTools(List.of("delete_order"));
        properties.getMcp().setServers(Map.of("fake", server));
        return properties;
    }

    private String javaCommand() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }
}
