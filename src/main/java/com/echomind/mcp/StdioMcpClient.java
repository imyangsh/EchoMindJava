package com.echomind.mcp;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class StdioMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpClient.class);
    /** 帧头累计上限：超过即判定协议错误（例如把 json-lines 服务器误配成 content-length），避免无限缓冲直至 OOM。 */
    private static final int MAX_HEADER_BYTES = 8 * 1024;
    /** 单帧消息体上限，防止恶意/异常的超大 Content-Length 直接打爆堆。 */
    private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    private static final Set<String> KNOWN_STDIO_FORMATS = Set.of("content-length", "json-lines", "jsonl");

    private final String serverName;
    private final EchoMindProperties.Server serverConfig;
    private final String protocolVersion;
    private final long defaultTimeoutMs;
    private final ObjectMapper objectMapper;
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private volatile Process process;
    private volatile OutputStream stdin;
    private Thread stdoutThread;
    private Thread stderrThread;
    private volatile boolean initialized;

    public StdioMcpClient(
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
    }

    public String serverName() {
        return serverName;
    }

    public synchronized void start() {
        if (process != null && process.isAlive() && initialized) {
            return;
        }
        if (serverConfig.getCommand() == null || serverConfig.getCommand().isBlank()) {
            throw new IllegalStateException("MCP server command is blank: " + serverName);
        }
        if (serverConfig.getStdioFormat() != null && !KNOWN_STDIO_FORMATS.contains(stdioFormat())) {
            //拼写错误的格式值（如 jsonlines）会静默退化为 content-length，
            //读线程永远等不到帧头、所有请求超时——启动时就把问题暴露出来。
            log.warn("Unknown stdio-format '{}' for MCP server {}; falling back to content-length framing. Known values: {}",
                    serverConfig.getStdioFormat(), serverName, KNOWN_STDIO_FORMATS);
        }
        //重启前清理上一次的进程与管道，避免 FD 泄漏
        closeProcessQuietly();
        try {
            List<String> command = new ArrayList<>();
            command.add(serverConfig.getCommand());
            command.addAll(serverConfig.getArgs());
            ProcessBuilder builder = new ProcessBuilder(command);
            if (serverConfig.getWorkingDir() != null && !serverConfig.getWorkingDir().isBlank()) {
                builder.directory(Path.of(serverConfig.getWorkingDir()).toFile());
            }
            builder.environment().putAll(serverConfig.getEnv());
            Process started = builder.start();
            process = started;
            stdin = started.getOutputStream();
            //子进程意外退出时立刻 fail 掉全部挂起请求，而不是让调用方硬等满超时
            started.onExit().thenRun(() -> {
                if (process == started) {
                    initialized = false;
                    failPending("MCP server process exited: " + serverName);
                }
            });
            stdoutThread = new Thread(() -> readStdout(started), "mcp-" + serverName + "-stdout");
            stdoutThread.setDaemon(true);
            stdoutThread.start();
            stderrThread = new Thread(() -> readStderr(started), "mcp-" + serverName + "-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();
            initialize();
            initialized = true;
            log.info("MCP stdio server initialized: {}", serverName);
        } catch (Exception ex) {
            close();
            throw new IllegalStateException("Failed to start MCP server " + serverName + ": " + ex.getMessage(), ex);
        }
    }

    public List<McpToolInfo> listTools() {
        start();
        JsonNode result = sendRequest("tools/list", objectMapper.createObjectNode(), timeoutMs());
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

    public JsonNode callTool(String toolName, Map<String, Object> arguments) {
        start();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", objectMapper.valueToTree(arguments == null ? Map.of() : arguments));
        return sendRequest("tools/call", params, timeoutMs());
    }

    public Map<String, Object> summary() {
        Process current = process;
        return Map.of(
                "name", serverName,
                "enabled", serverConfig.isEnabled(),
                "command", serverConfig.getCommand() == null ? "" : serverConfig.getCommand(),
                "args", serverConfig.getArgs(),
                "stdio_format", stdioFormat(),
                "running", current != null && current.isAlive(),
                "initialized", initialized
        );
    }

    private void initialize() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", protocolVersion == null || protocolVersion.isBlank() ? "2025-06-18" : protocolVersion);
        params.set("capabilities", objectMapper.createObjectNode());
        ObjectNode clientInfo = objectMapper.createObjectNode();
        clientInfo.put("name", "echomind-java");
        clientInfo.put("version", "0.1.0");
        params.set("clientInfo", clientInfo);
        sendRequest("initialize", params, timeoutMs());
        sendNotification("notifications/initialized", objectMapper.createObjectNode());
    }

    private JsonNode sendRequest(String method, JsonNode params, long timeoutMs) {
        long id = ids.getAndIncrement();
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params == null ? objectMapper.createObjectNode() : params);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        //writeMessage 也放进 try：写入失败（如 Broken pipe）时同样要清理 pending，
        //否则该 future 永久滞留在 map 中造成泄漏。
        try {
            writeMessage(request);
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw new IllegalStateException("MCP request timed out: " + serverName + "/" + method);
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("MCP request failed: " + serverName + "/" + method + ": " + ex.getMessage(), ex);
        } finally {
            pending.remove(id);
        }
    }

    private void sendNotification(String method, JsonNode params) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        request.set("params", params == null ? objectMapper.createObjectNode() : params);
        writeMessage(request);
    }

    private synchronized void writeMessage(JsonNode node) {
        try {
            String json = objectMapper.writeValueAsString(node);
            if (contentLengthFormat()) {
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                stdin.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                stdin.write(body);
            } else {
                stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            }
            stdin.flush();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to write MCP message to " + serverName + ": " + ex.getMessage(), ex);
        }
    }

    private void readStdout(Process source) {
        try {
            if (contentLengthFormat()) {
                readContentLengthStdout(source);
            } else {
                readJsonLineStdout(source);
            }
        } finally {
            //读线程退出（EOF、半帧、协议错误、异常）意味着连接已不可用：
            //统一 fail 掉全部挂起请求，避免调用方硬等满 30s 超时。
            if (process == source) {
                initialized = false;
            }
            failPending("MCP stdout closed: " + serverName);
        }
    }

    private void readJsonLineStdout(Process source) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handleMessage(line);
            }
        } catch (Exception ex) {
            if (source.isAlive()) {
                log.warn("MCP stdout reader failed for {}: {}", serverName, ex.getMessage());
            }
        }
    }

    private void readContentLengthStdout(Process source) {
        try (InputStream input = source.getInputStream()) {
            while (true) {
                byte[] headerBytes = readHeaderBytes(input);
                if (headerBytes.length == 0) {
                    return;
                }
                int contentLength = parseContentLength(headerBytes);
                if (contentLength <= 0) {
                    log.debug("Ignoring MCP stdout frame without Content-Length from {}: {}", serverName, new String(headerBytes, StandardCharsets.US_ASCII));
                    continue;
                }
                if (contentLength > MAX_BODY_BYTES) {
                    log.error("MCP frame from {} exceeds max body size ({} > {}); closing reader", serverName, contentLength, MAX_BODY_BYTES);
                    return;
                }
                byte[] body = input.readNBytes(contentLength);
                if (body.length < contentLength) {
                    return;
                }
                handleMessage(new String(body, StandardCharsets.UTF_8));
            }
        } catch (Exception ex) {
            if (source.isAlive()) {
                log.warn("MCP stdout reader failed for {}: {}", serverName, ex.getMessage());
            }
        }
    }

    private byte[] readHeaderBytes(InputStream input) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            buffer.write(value);
            if (buffer.size() > MAX_HEADER_BYTES) {
                throw new IllegalStateException("MCP frame header exceeds " + MAX_HEADER_BYTES
                        + " bytes; check stdio-format configuration for server " + serverName);
            }
            byte[] bytes = buffer.toByteArray();
            if (endsWith(bytes, new byte[]{'\r', '\n', '\r', '\n'})) {
                return Arrays.copyOf(bytes, bytes.length - 4);
            }
            if (endsWith(bytes, new byte[]{'\n', '\n'})) {
                return Arrays.copyOf(bytes, bytes.length - 2);
            }
        }
        return buffer.toByteArray();
    }

    private int parseContentLength(byte[] headerBytes) {
        String headers = new String(headerBytes, StandardCharsets.US_ASCII);
        for (String header : headers.split("\\r?\\n")) {
            int separator = header.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String name = header.substring(0, separator).trim();
            if (!"content-length".equalsIgnoreCase(name)) {
                continue;
            }
            try {
                return Integer.parseInt(header.substring(separator + 1).trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private boolean endsWith(byte[] bytes, byte[] suffix) {
        if (bytes.length < suffix.length) {
            return false;
        }
        for (int i = 0; i < suffix.length; i++) {
            if (bytes[bytes.length - suffix.length + i] != suffix[i]) {
                return false;
            }
        }
        return true;
    }

    private void readStderr(Process source) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("MCP stderr [{}]: {}", serverName, line);
            }
        } catch (Exception ex) {
            if (source.isAlive()) {
                log.debug("MCP stderr reader failed for {}: {}", serverName, ex.getMessage());
            }
        }
    }

    private void handleMessage(String line) {
        try {
            JsonNode message = objectMapper.readTree(line);
            //服务端发起的请求/通知（带 method 字段）不是我方请求的响应，
            //不能拿它的 id 去完成 pending future——双方 id 空间独立，
            //撞号会让 tools/call 拿到 null 结果并被当成成功。
            if (message.has("method")) {
                handleServerMessage(message);
                return;
            }
            JsonNode idNode = message.get("id");
            Long id = extractId(idNode);
            if (id == null) {
                return;
            }
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future == null) {
                return;
            }
            JsonNode error = message.get("error");
            if (error != null && !error.isNull()) {
                future.completeExceptionally(new IllegalStateException(error.toString()));
                return;
            }
            JsonNode result = message.get("result");
            future.complete(result == null ? objectMapper.getNodeFactory().nullNode() : result);
        } catch (Exception ex) {
            log.debug("Ignoring non-JSON MCP stdout line from {}: {}", serverName, line);
        }
    }

    /** 兼容把数字 id 回显成字符串（如 "1"）的服务端实现。 */
    private Long extractId(JsonNode idNode) {
        if (idNode == null || idNode.isNull()) {
            return null;
        }
        if (idNode.canConvertToLong()) {
            return idNode.asLong();
        }
        if (idNode.isTextual()) {
            try {
                return Long.parseLong(idNode.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void handleServerMessage(JsonNode message) {
        String method = message.path("method").asText("");
        JsonNode idNode = message.get("id");
        if (idNode == null || idNode.isNull()) {
            //通知：记录后忽略
            log.debug("MCP notification from {}: {}", serverName, method);
            return;
        }
        //服务端请求（roots/list、sampling/createMessage 等）本客户端未实现，按 JSON-RPC 规范回 -32601
        log.debug("Rejecting unsupported MCP server request from {}: {}", serverName, method);
        try {
            ObjectNode response = objectMapper.createObjectNode();
            response.put("jsonrpc", "2.0");
            response.set("id", idNode.deepCopy());
            ObjectNode error = objectMapper.createObjectNode();
            error.put("code", -32601);
            error.put("message", "Method not supported by echomind-java client: " + method);
            response.set("error", error);
            writeMessage(response);
        } catch (Exception ex) {
            log.debug("Failed to respond to MCP server request {}: {}", method, ex.getMessage());
        }
    }

    private void failPending(String reason) {
        pending.values().forEach(future -> future.completeExceptionally(new IllegalStateException(reason)));
        pending.clear();
    }

    private long timeoutMs() {
        if (serverConfig.getTimeoutMs() > 0) {
            return serverConfig.getTimeoutMs();
        }
        return defaultTimeoutMs > 0 ? defaultTimeoutMs : Duration.ofSeconds(30).toMillis();
    }

    private boolean contentLengthFormat() {
        return !"json-lines".equals(stdioFormat()) && !"jsonl".equals(stdioFormat());
    }

    private String stdioFormat() {
        return serverConfig.getStdioFormat() == null ? "content-length" : serverConfig.getStdioFormat().toLowerCase(Locale.ROOT).trim();
    }

    @Override
    public synchronized void close() {
        initialized = false;
        failPending("MCP client closed");
        closeProcessQuietly();
    }

    private void closeProcessQuietly() {
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (Exception ignored) {
        }
        Process current = process;
        if (current != null && current.isAlive()) {
            current.destroy();
            try {
                if (!current.waitFor(2, TimeUnit.SECONDS)) {
                    current.destroyForcibly();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                current.destroyForcibly();
            } catch (Exception ignored) {
                current.destroyForcibly();
            }
        }
        process = null;
        stdin = null;
    }
}
