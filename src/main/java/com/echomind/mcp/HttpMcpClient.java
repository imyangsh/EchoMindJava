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
    /** Streamable HTTP 规范要求 initialize 之后的请求带回服务端下发的 Mcp-Session-Id。 */
    private volatile String sessionId;

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
        params.put("protocolVersion", effectiveProtocolVersion());
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
                "url", sanitizeUrl(serverConfig.getUrl()),
                "initialized", initialized
        );
    }

    /** 状态接口对外暴露 url 时去掉 query 与 userinfo，避免 token 泄漏。 */
    private String sanitizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            URI sanitized = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null);
            return sanitized.toString();
        } catch (Exception ex) {
            return "(unparseable url)";
        }
    }

    private JsonNode sendRequest(String method, JsonNode params) {
        long id = ids.getAndIncrement();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params == null ? objectMapper.createObjectNode() : params);
        JsonNode message = postJson(request, true, id);
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
        postJson(request, false, null);
    }

    private JsonNode postJson(JsonNode payload, boolean expectResponse, Long expectedId) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(serverConfig.getUrl()))
                    .timeout(Duration.ofMillis(timeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "sse".equals(transport()) ? "text/event-stream, application/json" : "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", effectiveProtocolVersion())
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            String session = sessionId;
            if (session != null && !session.isBlank()) {
                builder.header("Mcp-Session-Id", session);
            }
            serverConfig.getHeaders().forEach(builder::header);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            //Streamable HTTP：保存 initialize 响应中的会话 id，后续请求带回
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> sessionId = value);
            if (response.statusCode() == 404 && initialized) {
                //会话失效：按规范应重新 initialize
                initialized = false;
                sessionId = null;
                throw new IllegalStateException("MCP session expired (HTTP 404), will re-initialize on next call");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode() + ": " + truncate(response.body(), 500));
            }
            if (!expectResponse || response.body() == null || response.body().isBlank()) {
                return objectMapper.createObjectNode();
            }
            return parseResponseBody(response.body(), response.headers().firstValue("content-type").orElse(""), expectedId);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("MCP HTTP request failed: " + serverName + ": " + ex.getMessage(), ex);
        }
    }

    private JsonNode parseResponseBody(String body, String contentType, Long expectedId) throws Exception {
        if (contentType.toLowerCase(Locale.ROOT).contains("text/event-stream") || body.lines().anyMatch(line -> line.startsWith("data:"))) {
            //按 SSE 规范以空行分隔事件、拼接每个事件的多行 data；
            //从所有事件中选出与请求 id 匹配的那条响应，
            //旧实现取“最后一条 data 行”，最后一条是 ping/notification 时会拿到错误消息体。
            JsonNode matched = null;
            JsonNode lastParsed = null;
            StringBuilder data = new StringBuilder();
            List<String> lines = new ArrayList<>(body.lines().toList());
            lines.add(""); //确保最后一个事件被冲刷
            for (String line : lines) {
                if (line.isBlank()) {
                    if (!data.isEmpty()) {
                        String eventData = data.toString();
                        data.setLength(0);
                        if (!"[DONE]".equals(eventData.trim())) {
                            try {
                                JsonNode node = objectMapper.readTree(eventData);
                                lastParsed = node;
                                if (matchesId(node, expectedId)) {
                                    matched = node;
                                }
                            } catch (Exception ignored) {
                                //忽略非 JSON 的事件数据
                            }
                        }
                    }
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
            if (matched != null) {
                return matched;
            }
            return lastParsed == null ? objectMapper.createObjectNode() : lastParsed;
        }
        JsonNode node = objectMapper.readTree(body);
        if (expectedId != null && node.has("id") && !matchesId(node, expectedId)) {
            throw new IllegalStateException("MCP response id mismatch: expected " + expectedId + ", got " + node.get("id"));
        }
        return node;
    }

    private boolean matchesId(JsonNode node, Long expectedId) {
        if (expectedId == null || node == null) {
            return false;
        }
        JsonNode idNode = node.get("id");
        if (idNode == null || idNode.isNull()) {
            return false;
        }
        if (idNode.canConvertToLong()) {
            return idNode.asLong() == expectedId;
        }
        return idNode.isTextual() && idNode.asText().trim().equals(String.valueOf(expectedId));
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...(truncated)";
    }

    private String effectiveProtocolVersion() {
        return protocolVersion == null || protocolVersion.isBlank() ? "2025-06-18" : protocolVersion;
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
        sessionId = null;
        //Java 21 起 HttpClient 实现 AutoCloseable：不关闭会在每次 reload 后
        //泄漏 selector 线程与连接池，最终 OOM: unable to create native thread。
        try {
            httpClient.close();
        } catch (Exception ex) {
            log.debug("Failed to close HttpClient for {}: {}", serverName, ex.getMessage());
        }
    }
}
