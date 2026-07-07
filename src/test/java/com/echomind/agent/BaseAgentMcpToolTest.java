package com.echomind.agent;

import com.echomind.llm.LlmGateway;
import com.echomind.config.EchoMindProperties;
import com.echomind.mcp.McpCallResult;
import com.echomind.mcp.McpClientManager;
import com.echomind.mcp.McpToolInfo;
import com.echomind.skill.SkillManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BaseAgentMcpToolTest {

    @Test
    void agentCallsMcpToolAndReturnsTrace() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        McpClientManager mcp = new FakeMcpClientManager(objectMapper, List.of(new McpToolInfo(
                "orders",
                "lookup_order",
                "orders.lookup_order",
                "查询订单状态",
                objectMapper.readTree("{\"type\":\"object\",\"properties\":{\"order_id\":{\"type\":\"string\"}}}")
        )), objectMapper.readTree("{\"status\":\"shipped\"}"));

        AtomicInteger calls = new AtomicInteger();
        LlmGateway llm = (systemPrompt, userPrompt, temperature, maxTokens) -> {
            if (calls.getAndIncrement() == 0) {
                return "{\"use_tool\":true,\"server\":\"orders\",\"tool\":\"lookup_order\",\"arguments\":{\"order_id\":\"12345\"},\"reason\":\"需要查询实时订单状态\"}";
            }
            return "订单 12345 已发货。";
        };
        SkillManager skillManager = new SkillManager(new EchoMindProperties(), objectMapper);
        AgentToolRegistry registry = new AgentToolRegistry(mcp, null, objectMapper);
        AgentToolService toolService = new AgentToolService(registry, skillManager, objectMapper);

        GeneralAgent agent = new GeneralAgent(llm, toolService);
        AgentResponse response = agent.handle(AgentRequest.of("查一下订单 12345", "u1", "c1", "", List.of()));

        assertThat(response.content()).contains("已发货");
        assertThat(response.toolCalls()).hasSize(1);
        ToolCallTrace trace = response.toolCalls().getFirst();
        assertThat(trace.server()).isEqualTo("orders");
        assertThat(trace.tool()).isEqualTo("lookup_order");
        assertThat(trace.arguments()).containsEntry("order_id", "12345");
        assertThat(trace.success()).isTrue();
        assertThat(trace.latencyMs()).isEqualTo(12);
    }

    private static class FakeMcpClientManager extends McpClientManager {
        private final List<McpToolInfo> tools;
        private final JsonNode result;

        FakeMcpClientManager(ObjectMapper objectMapper, List<McpToolInfo> tools, JsonNode result) {
            super(new EchoMindProperties(), objectMapper);
            this.tools = tools;
            this.result = result;
        }

        @Override
        public boolean autoToolEnabled() {
            return true;
        }

        @Override
        public int maxToolIterations() {
            return 1;
        }

        @Override
        public int maxToolPromptChars() {
            return 4000;
        }

        @Override
        public List<McpToolInfo> listTools() {
            return tools;
        }

        @Override
        public McpCallResult callTool(String serverName, String toolName, Map<String, Object> arguments) {
            return new McpCallResult(true, serverName, toolName, result, null, 12);
        }
    }
}
