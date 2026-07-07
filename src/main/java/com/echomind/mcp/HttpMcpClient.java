package com.echomind.mcp;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class HttpMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMcpClient.class);

    private final String serverName;
    private final EchoMindProperties.Server serverConfig;
    private final String protocolVersion;
    private final long defaultTimeoutMs;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final AtomicLong ids = new AtomicLong(1);
    private volatile boolean initialized;

    public HttpMcpClient(
            String serverName,
            EchoMindProperties.Server serverConfig,
            String protocolVersion,
            long defaultTimeoutMs,
            ObjectMapper objectMapper
    ) {
        this.serverName = serverName;
        this.serverConfig = serverConfig;
        this.protocolVersion = protocolVersion;
        this.defaultTimeoutMs = defaultTimeoutMs;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs())).build();
    }

    @Override
    public String serverName() {
        return serverName;
    }

    public synchronized void start() {
        if (initialized) {
            return;
        }
        if (serverConfig.getUrl() == null || serverConfig.getUrl().isBlank()) {
            throw new IllegalStateException("MCP HTTP/SSE server url is blank: " + serverName);
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", protocolVersion == null || protocolVersion.isBlank() ? "2025-06-18" : protocolVersion);
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "echomind-java");
        clientInfo.put("version", "0.1.0");
        params.set("clientInfo", clientInfo);
        sendRequest("initialize", params);
        sendNotification("notifications/initialized", objectMapper.createObjectNode());
        initialized = true;
        log.info("MCP {} server initialized: {}", transport(), serverName);
    }

    @Override
    public List<McpToolInfo> listTools() {
        start();
        JsonNode result = sendRequest("tools/list", objectMapper.createObjectNode());
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) {
            return List.of();
        }
        List<McpToolInfo> values = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            values.add(new McpToolInfo(
                    serverName,
                    name,
                    serverName + "." + name,
                    tool.path("description").asText(""),
                    tool.path("inputSchema")
            ));
        }
        return values;
    }

    @Override
    public JsonNode callTool(String toolName, Map<String, Object> arguments) {
        start();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", objectMapper.valueToTree(arguments == null ? Map.of() : arguments));
        return sendRequest("tools/call", params);
    }

    @Override
    public Map<String, Object> summary() {
        return Map.of(
                "name", serverName,
                "enabled", serverConfig.isEnabled(),
                "transport", transport(),
                "url", serverConfig.getUrl() == null ? "" : serverConfig.getUrl(),
                "initialized", initialized
        );
    }

    private JsonNode sendRequest(String method, JsonNode params) {
        long id = ids.getAndIncrement();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params == null ? objectMapper.createObjectNode() : params);
        JsonNode message = postJson(request, true);
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            throw new IllegalStateException(error.toString());
        }
        JsonNode result = message.get("result");
        return result == null ? objectMapper.getNodeFactory().nullNode() : result;
    }

    private void sendNotification(String method, JsonNode params) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        request.set("params", params == null ? objectMapper.createObjectNode() : params);
        postJson(request, false);
    }

    private JsonNode postJson(JsonNode payload, boolean expectResponse) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(serverConfig.getUrl()))
                    .timeout(Duration.ofMillis(timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "sse".equals(transport()) ? "text/event-stream, application/json" : "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            serverConfig.getHeaders().forEach(builder::header);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
            }
            if (!expectResponse || response.body() == null || response.body().isBlank()) {
                return objectMapper.createObjectNode();
            }
            return parseResponseBody(response.body(), response.headers().firstValue("content-type").orElse(""));
        } catch (Exception ex) {
            throw new IllegalStateException("MCP HTTP request failed: " + serverName + ": " + ex.getMessage(), ex);
        }
    }

    private JsonNode parseResponseBody(String body, String contentType) throws Exception {
        if (contentType.toLowerCase(Locale.ROOT).contains("text/event-stream") || body.lines().anyMatch(line -> line.startsWith("data:"))) {
            String data = body.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring("data:".length()).trim())
                    .filter(line -> !line.isBlank() && !"[DONE]".equals(line))
                    .reduce((first, second) -> second)
                    .orElse("{}");
            return objectMapper.readTree(data);
        }
        return objectMapper.readTree(body);
    }

    private long timeoutMs() {
        if (serverConfig.getTimeoutMs() > 0) {
            return serverConfig.getTimeoutMs();
        }
        return defaultTimeoutMs > 0 ? defaultTimeoutMs : Duration.ofSeconds(30).toMillis();
    }

    private String transport() {
        String transport = serverConfig.getTransport();
        return transport == null || transport.isBlank() ? "http" : transport.toLowerCase(Locale.ROOT).trim();
    }

    @Override
    public void close() {
        initialized = false;
    }
}
