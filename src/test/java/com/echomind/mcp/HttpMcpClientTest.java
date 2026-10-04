package com.echomind.mcp;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HttpMcpClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void supportsHttpJsonRpcTransport() throws Exception {
        try (TestMcpHttpServer server = TestMcpHttpServer.start(false)) {
            McpClientManager manager = manager("http", server.url());
            try {
                manager.reload();

                assertThat(manager.listTools()).extracting(McpToolInfo::name).containsExactly("echo");
                McpCallResult result = manager.callTool("remote", "echo", Map.of("text", "hello"));

                assertThat(result.success()).isTrue();
                assertThat(result.result().path("content").get(0).path("text").asText()).isEqualTo("hello");
            } finally {
                manager.destroy();
            }
        }
    }

    @Test
    void supportsSseJsonRpcResponses() throws Exception {
        try (TestMcpHttpServer server = TestMcpHttpServer.start(true)) {
            McpClientManager manager = manager("sse", server.url());
            try {
                manager.reload();

                McpCallResult result = manager.callTool("remote", "echo", Map.of("text", "streamed"));

                assertThat(result.success()).isTrue();
                assertThat(result.result().path("content").get(0).path("text").asText()).isEqualTo("streamed");
            } finally {
                manager.destroy();
            }
        }
    }

    private McpClientManager manager(String transport, String url) {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getMcp().setEnabled(true);
        properties.getMcp().setRequestTimeoutMs(5000);
        EchoMindProperties.Server remote = new EchoMindProperties.Server();
        remote.setTransport(transport);
        remote.setUrl(url);
        remote.setAllowedTools(List.of("echo"));
        //fail-closed 策略下，自动调用与 Agent 可见性要求工具显式声明为只读
        remote.setReadOnlyTools(List.of("echo"));
        properties.getMcp().setServers(Map.of("remote", remote));
        return new McpClientManager(properties, objectMapper);
    }

    private static class TestMcpHttpServer implements AutoCloseable {
        private final HttpServer server;

        private TestMcpHttpServer(HttpServer server) {
            this.server = server;
        }

        static TestMcpHttpServer start(boolean sse) throws IOException {
            ObjectMapper mapper = new ObjectMapper();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/mcp", exchange -> {
                try {
                    JsonNode request = mapper.readTree(exchange.getRequestBody());
                    if (!request.has("id")) {
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    ObjectNode response = mapper.createObjectNode();
                    response.put("jsonrpc", "2.0");
                    response.set("id", request.get("id"));
                    response.set("result", resultFor(mapper, request));
                    byte[] body = sse
                            ? ("event: message\n" + "data: " + mapper.writeValueAsString(response) + "\n\n").getBytes(StandardCharsets.UTF_8)
                            : mapper.writeValueAsBytes(response);
                    exchange.getResponseHeaders().add("Content-Type", sse ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return new TestMcpHttpServer(server);
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static JsonNode resultFor(ObjectMapper mapper, JsonNode request) {
            return switch (request.path("method").asText("")) {
                case "initialize" -> mapper.createObjectNode()
                        .put("protocolVersion", "2025-06-18")
                        .set("capabilities", mapper.createObjectNode());
                case "tools/list" -> toolsList(mapper);
                case "tools/call" -> toolsCall(mapper, request.path("params"));
                default -> mapper.createObjectNode();
            };
        }

        private static ObjectNode toolsList(ObjectMapper mapper) {
            ObjectNode result = mapper.createObjectNode();
            ArrayNode tools = result.putArray("tools");
            ObjectNode tool = tools.addObject();
            tool.put("name", "echo");
            tool.put("description", "Echo text");
            ObjectNode schema = tool.putObject("inputSchema");
            schema.put("type", "object");
            schema.putArray("required").add("text");
            schema.putObject("properties").putObject("text").put("type", "string");
            return result;
        }

        private static ObjectNode toolsCall(ObjectMapper mapper, JsonNode params) {
            ObjectNode result = mapper.createObjectNode();
            result.put("isError", false);
            ObjectNode content = result.putArray("content").addObject();
            content.put("type", "text");
            content.put("text", params.path("arguments").path("text").asText(""));
            return result;
        }
    }
}
