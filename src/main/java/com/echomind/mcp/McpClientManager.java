package com.echomind.mcp;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class McpClientManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    private final EchoMindProperties properties;
    private final ObjectMapper objectMapper;
    /**
     * volatile 不可变引用：reload 时先构建新 map，再整体原子替换，
     * 最后延迟关闭旧客户端。旧实现是原地 clear + put 的 LinkedHashMap，
     * 与并发中的 listTools/callTool 同时执行会抛 ConcurrentModificationException
     * 或让调用方拿到已关闭的客户端。
     */
    private volatile Map<String, McpClient> clients = Map.of();
    private final Map<String, List<McpToolInfo>> toolCache = new ConcurrentHashMap<>();
    private final McpSchemaValidator schemaValidator;

    public McpClientManager(EchoMindProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.schemaValidator = new McpSchemaValidator(objectMapper);
    }

    @PostConstruct
    public void init() {
        reload();
    }

    public synchronized Map<String, Object> reload() {
        Map<String, McpClient> oldClients = clients;
        Map<String, McpClient> newClients = new LinkedHashMap<>();
        EchoMindProperties.Mcp mcp = properties.getMcp();
        if (mcp.isEnabled()) {
            mcp.getServers().forEach((name, server) -> {
                if (!server.isEnabled()) {
                    return;
                }
                String transport = transport(server);
                if ("stdio".equals(transport) && (server.getCommand() == null || server.getCommand().isBlank())) {
                    log.warn("MCP server {} skipped because command is blank", name);
                    return;
                }
                if (("http".equals(transport) || "sse".equals(transport)) && (server.getUrl() == null || server.getUrl().isBlank())) {
                    log.warn("MCP server {} skipped because url is blank", name);
                    return;
                }
                newClients.put(name, createClient(name, server));
            });
        } else {
            log.info("MCP protocol client is disabled");
        }
        //原子替换引用后再关闭旧客户端，正在执行的调用要么用旧实例、要么用新实例，不会读到中间状态
        clients = Map.copyOf(newClients);
        toolCache.clear();
        closeQuietly(oldClients);
        log.info("MCP clients configured: {}", clients.keySet());
        return summary();
    }

    private void closeQuietly(Map<String, McpClient> toClose) {
        //逐个 try/catch：任何一个 close 抛异常不能中断其余客户端的清理，
        //否则会残留子进程与守护线程。
        toClose.forEach((name, client) -> {
            try {
                client.close();
            } catch (Exception ex) {
                log.warn("Failed to close MCP client {}: {}", name, ex.getMessage());
            }
        });
    }

    public List<McpToolInfo> listTools() {
        return listTools(true);
    }

    public List<McpToolInfo> listAllTools() {
        return listTools(false);
    }

    public List<Map<String, Object>> listAllToolSummaries() {
        return listAllTools().stream().map(this::summarizeWithPolicy).toList();
    }

    private Map<String, Object> summarizeWithPolicy(McpToolInfo tool) {
        Map<String, Object> summary = new LinkedHashMap<>(tool.summary());
        EchoMindProperties.Server server = properties.getMcp().getServers().get(tool.serverName());
        if (server == null) {
            summary.put("policy", Map.of(
                    "agent_visible", false,
                    "automatic_allowed", false,
                    "automatic_reason", "MCP server not configured: " + tool.serverName(),
                    "manual_allowed", false,
                    "manual_reason", "MCP server not configured: " + tool.serverName()
            ));
            return summary;
        }
        McpToolPolicy.ToolPolicyStatus status = new McpToolPolicy(server).status(tool.name());
        summary.put("policy", Map.of(
                "agent_visible", status.agentVisible(),
                "automatic_allowed", status.automaticAllowed(),
                "automatic_reason", status.automaticReason(),
                "manual_allowed", status.manualAllowed(),
                "manual_reason", status.manualReason()
        ));
        return summary;
    }

    private List<McpToolInfo> listTools(boolean agentVisibleOnly) {
        if (!properties.getMcp().isEnabled()) {
            return List.of();
        }
        List<McpToolInfo> tools = new ArrayList<>();
        clients.forEach((name, client) -> {
            try {
                EchoMindProperties.Server server = properties.getMcp().getServers().get(name);
                McpToolPolicy policy = new McpToolPolicy(server);
                List<McpToolInfo> serverTools = toolsFor(name, client).stream()
                        .filter(tool -> !agentVisibleOnly || policy.visibleToAgent(tool.name()))
                        .toList();
                tools.addAll(serverTools);
            } catch (Exception ex) {
                log.warn("Failed to list MCP tools for {}: {}", name, ex.getMessage());
            }
        });
        return tools;
    }

    public boolean autoToolEnabled() {
        return properties.getMcp().isEnabled() && properties.getMcp().isAutoToolEnabled();
    }

    public int maxToolIterations() {
        return Math.max(0, properties.getMcp().getMaxToolIterations());
    }

    public int maxToolPromptChars() {
        return Math.max(1000, properties.getMcp().getMaxToolPromptChars());
    }

    public McpCallResult callTool(String serverName, String toolName, Map<String, Object> arguments) {
        return callTool(serverName, toolName, arguments, true);
    }

    public McpCallResult callToolManually(String serverName, String toolName, Map<String, Object> arguments) {
        return callTool(serverName, toolName, arguments, false);
    }

    private McpCallResult callTool(String serverName, String toolName, Map<String, Object> arguments, boolean automatic) {
        if (!properties.getMcp().isEnabled()) {
            return new McpCallResult(false, serverName, toolName, objectMapper.getNodeFactory().nullNode(), "MCP is disabled", 0);
        }
        McpClient client = clients.get(serverName);
        if (client == null) {
            return new McpCallResult(false, serverName, toolName, objectMapper.getNodeFactory().nullNode(), "MCP server not configured: " + serverName, 0);
        }
        Instant start = Instant.now();
        try {
            EchoMindProperties.Server server = properties.getMcp().getServers().get(serverName);
            McpToolPolicy.PolicyCheck policy = new McpToolPolicy(server).canCall(toolName, automatic);
            if (!policy.allowed()) {
                return new McpCallResult(false, serverName, toolName, objectMapper.getNodeFactory().nullNode(), policy.reason(), Duration.between(start, Instant.now()).toMillis());
            }
            McpToolInfo tool = toolsFor(serverName, client).stream()
                    .filter(item -> item.name().equals(toolName))
                    .findFirst()
                    .orElse(null);
            if (tool == null) {
                return new McpCallResult(false, serverName, toolName, objectMapper.getNodeFactory().nullNode(), "MCP tool not found: " + toolName, Duration.between(start, Instant.now()).toMillis());
            }
            McpSchemaValidator.ValidationResult validation = schemaValidator.validate(tool.inputSchema(), arguments);
            if (!validation.valid()) {
                return new McpCallResult(false, serverName, toolName, objectMapper.getNodeFactory().nullNode(), "invalid tool arguments: " + String.join("; ", validation.errors()), Duration.between(start, Instant.now()).toMillis());
            }
            JsonNode result = client.callTool(toolName, arguments);
            if (result != null && result.path("isError").asBoolean(false)) {
                return new McpCallResult(false, serverName, toolName, result,
                        "MCP tool returned an error", Duration.between(start, Instant.now()).toMillis());
            }
            return new McpCallResult(true, serverName, toolName, result, null, Duration.between(start, Instant.now()).toMillis());
        } catch (Exception ex) {
            //吞掉异常前先落日志，且 getMessage() 可能为 null（如 NPE），回退到 toString
            log.warn("MCP tool call failed: {}/{}", serverName, toolName, ex);
            String error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            return new McpCallResult(false, serverName, toolName, objectMapper.getNodeFactory().nullNode(), error, Duration.between(start, Instant.now()).toMillis());
        }
    }

    private McpClient createClient(String name, EchoMindProperties.Server server) {
        EchoMindProperties.Mcp mcp = properties.getMcp();
        String transport = transport(server);
        if ("http".equals(transport) || "sse".equals(transport)) {
            return new HttpMcpClient(name, server, mcp.getProtocolVersion(), mcp.getRequestTimeoutMs(), objectMapper);
        }
        return new StdioMcpClient(name, server, mcp.getProtocolVersion(), mcp.getRequestTimeoutMs(), objectMapper);
    }

    private String transport(EchoMindProperties.Server server) {
        String transport = server.getTransport();
        return transport == null || transport.isBlank() ? "stdio" : transport.toLowerCase(Locale.ROOT).trim();
    }

    private List<McpToolInfo> toolsFor(String serverName, McpClient client) {
        //不能用 computeIfAbsent：client.listTools() 可能拉起子进程并做最长 30s 的
        //同步 RPC，在 mapping function 里执行会长时间持有 CHM 的 bin 锁。
        //先无锁读，miss 时在锁外计算，再 putIfAbsent 回填。
        List<McpToolInfo> cached = toolCache.get(serverName);
        if (cached != null) {
            return cached;
        }
        List<McpToolInfo> tools = List.copyOf(client.listTools());
        List<McpToolInfo> existing = toolCache.putIfAbsent(serverName, tools);
        return existing != null ? existing : tools;
    }

    public Map<String, Object> summary() {
        return Map.of(
                "enabled", properties.getMcp().isEnabled(),
                "protocol_version", properties.getMcp().getProtocolVersion(),
                "servers", clients.values().stream().map(McpClient::summary).toList()
        );
    }

    @Override
    public synchronized void destroy() {
        Map<String, McpClient> toClose = clients;
        clients = Map.of();
        toolCache.clear();
        closeQuietly(toClose);
    }
}
