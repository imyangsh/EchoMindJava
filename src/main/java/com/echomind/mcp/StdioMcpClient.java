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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class StdioMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpClient.class);

    private final String serverName;
    private final EchoMindProperties.Server serverConfig;
    private final String protocolVersion;
    private final long defaultTimeoutMs;
    private final ObjectMapper objectMapper;
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private Process process;
    private OutputStream stdin;
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
        try {
            List<String> command = new ArrayList<>();
            command.add(serverConfig.getCommand());
            command.addAll(serverConfig.getArgs());
            ProcessBuilder builder = new ProcessBuilder(command);
            if (serverConfig.getWorkingDir() != null && !serverConfig.getWorkingDir().isBlank()) {
                builder.directory(Path.of(serverConfig.getWorkingDir()).toFile());
            }
            builder.environment().putAll(serverConfig.getEnv());
            process = builder.start();
            stdin = process.getOutputStream();
            stdoutThread = new Thread(this::readStdout, "mcp-" + serverName + "-stdout");
            stdoutThread.setDaemon(true);
            stdoutThread.start();
            stderrThread = new Thread(this::readStderr, "mcp-" + serverName + "-stderr");
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
        return Map.of(
                "name", serverName,
                "enabled", serverConfig.isEnabled(),
                "command", serverConfig.getCommand() == null ? "" : serverConfig.getCommand(),
                "args", serverConfig.getArgs(),
                "stdio_format", stdioFormat(),
                "running", process != null && process.isAlive(),
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
        writeMessage(request);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            pending.remove(id);
            throw new IllegalStateException("MCP request timed out: " + serverName + "/" + method);
        } catch (Exception ex) {
            pending.remove(id);
            throw new IllegalStateException("MCP request failed: " + serverName + "/" + method + ": " + ex.getMessage(), ex);
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

    private void readStdout() {
        if (contentLengthFormat()) {
            readContentLengthStdout();
        } else {
            readJsonLineStdout();
        }
    }

    private void readJsonLineStdout() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handleMessage(line);
            }
        } catch (Exception ex) {
            if (process != null && process.isAlive()) {
                log.warn("MCP stdout reader failed for {}: {}", serverName, ex.getMessage());
            }
        }
    }

    private void readContentLengthStdout() {
        try (InputStream input = process.getInputStream()) {
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
                byte[] body = input.readNBytes(contentLength);
                if (body.length < contentLength) {
                    return;
                }
                handleMessage(new String(body, StandardCharsets.UTF_8));
            }
        } catch (Exception ex) {
            if (process != null && process.isAlive()) {
                log.warn("MCP stdout reader failed for {}: {}", serverName, ex.getMessage());
            }
        }
    }

    private byte[] readHeaderBytes(InputStream input) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            buffer.write(value);
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

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("MCP stderr [{}]: {}", serverName, line);
            }
        } catch (Exception ex) {
            if (process != null && process.isAlive()) {
                log.debug("MCP stderr reader failed for {}: {}", serverName, ex.getMessage());
            }
        }
    }

    private void handleMessage(String line) {
        try {
            JsonNode message = objectMapper.readTree(line);
            JsonNode idNode = message.get("id");
            if (idNode == null || !idNode.canConvertToLong()) {
                return;
            }
            CompletableFuture<JsonNode> future = pending.remove(idNode.asLong());
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
        pending.values().forEach(future -> future.completeExceptionally(new IllegalStateException("MCP client closed")));
        pending.clear();
        try {
            if (stdin != null) {
                stdin.close();
            }
        } catch (Exception ignored) {
        }
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (Exception ignored) {
                process.destroyForcibly();
            }
        }
        process = null;
        stdin = null;
    }
}
