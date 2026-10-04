package com.echomind.agent;

import com.echomind.config.EchoMindProperties;
import com.echomind.knowledge.SearchResult;
import com.echomind.mcp.McpCallResult;
import com.echomind.mcp.McpClientManager;
import com.echomind.mcp.McpToolInfo;
import com.echomind.mcp.McpSchemaValidator;
import com.echomind.tool.KnowledgeToolManager;
import com.echomind.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class AgentToolRegistry {

    public static final String INTERNAL_SERVER = "internal";
    public static final String KNOWLEDGE_SEARCH = "knowledge_search";

    private final McpClientManager mcpClientManager;
    private final KnowledgeToolManager knowledgeToolManager;
    private final ObjectMapper objectMapper;
    private final EchoMindProperties properties;
    private final AgentToolDefinition knowledgeTool;
    private final McpSchemaValidator schemaValidator;

    public AgentToolRegistry(McpClientManager mcpClientManager, KnowledgeToolManager knowledgeToolManager, ObjectMapper objectMapper) {
        this(mcpClientManager, knowledgeToolManager, objectMapper, new EchoMindProperties());
    }

    @Autowired
    public AgentToolRegistry(McpClientManager mcpClientManager, KnowledgeToolManager knowledgeToolManager,
                             ObjectMapper objectMapper, EchoMindProperties properties) {
        this.mcpClientManager = mcpClientManager;
        this.knowledgeToolManager = knowledgeToolManager;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.knowledgeTool = knowledgeSearchTool();
        this.schemaValidator = new McpSchemaValidator(objectMapper);
    }

    public List<AgentToolDefinition> listAgentTools() {
        return listAgentTools(AgentType.GENERAL);
    }

    public List<AgentToolDefinition> listAgentTools(AgentType agentType) {
        List<AgentToolDefinition> tools = new ArrayList<>();
        if (mcpClientManager != null && mcpClientManager.autoToolEnabled()) {
            for (McpToolInfo tool : mcpClientManager.listTools()) {
                tools.add(new AgentToolDefinition(
                        tool.serverName(),
                        tool.name(),
                        tool.qualifiedName(),
                        tool.description(),
                        tool.inputSchema(),
                        "mcp"
                ));
            }
        }
        //knowledgeToolManager 可能为 null（部分测试/裁剪部署），
        //此时不能对外声明一个执行时必然 NPE 的工具
        if (knowledgeToolManager != null) {
            tools.add(knowledgeTool);
        }
        return tools.stream().filter(tool -> allowedFor(agentType, tool.server(), tool.name())).toList();
    }

    public ToolExecution execute(String server, String tool, Map<String, Object> arguments) {
        return execute(AgentType.GENERAL, server, tool, arguments);
    }

    public ToolExecution execute(AgentType agentType, String server, String tool, Map<String, Object> arguments) {
        if (!allowedFor(agentType, server, tool)) {
            return new ToolExecution(false, server, tool, objectMapper.nullNode(), "tool outside agent capability scope", 0);
        }
        if (INTERNAL_SERVER.equals(server) && KNOWLEDGE_SEARCH.equals(tool)) {
            if (knowledgeToolManager == null) {
                return new ToolExecution(false, server, tool, objectMapper.getNodeFactory().nullNode(), "knowledge search is unavailable", 0);
            }
            return executeKnowledgeSearch(arguments);
        }
        if (mcpClientManager == null || !mcpClientManager.autoToolEnabled()) {
            return new ToolExecution(false, server, tool, objectMapper.getNodeFactory().nullNode(), "MCP is unavailable", 0);
        }
        McpCallResult result = mcpClientManager.callTool(server, tool, arguments);
        if (result.result() != null && result.result().path("isError").asBoolean(false)) {
            return new ToolExecution(false, result.serverName(), result.toolName(), result.result(),
                    "MCP tool reported a business error", result.latencyMs());
        }
        return new ToolExecution(result.success(), result.serverName(), result.toolName(), result.result(), result.error(), result.latencyMs());
    }

    /** Missing role configuration preserves the original MCP policy; an explicit empty list denies all. */
    public boolean allowedFor(AgentType agentType, String server, String tool) {
        Map<String, List<String>> capabilities = properties.getAgent().getToolAllowlist();
        String role = agentType.name().toLowerCase(Locale.ROOT);
        List<String> patterns = capabilities.get(role);
        if (patterns == null) return true;
        String qualified = server + "." + tool;
        return patterns.stream().anyMatch(pattern -> matchesCapability(pattern, qualified));
    }

    private boolean matchesCapability(String pattern, String qualified) {
        if (pattern == null) return false;
        StringBuilder regex = new StringBuilder();
        for (char c : pattern.trim().toCharArray()) {
            if (c == '*') regex.append(".*");
            else if (c == '?') regex.append('.');
            else regex.append(Pattern.quote(String.valueOf(c)));
        }
        return qualified.matches(regex.toString());
    }

    public int maxIterations() {
        return mcpClientManager == null ? 2 : mcpClientManager.maxToolIterations();
    }

    public int maxToolPromptChars() {
        return mcpClientManager == null ? 6000 : mcpClientManager.maxToolPromptChars();
    }

    private AgentToolDefinition knowledgeSearchTool() {
        JsonNode schema;
        try {
            schema = objectMapper.readTree("""
                    {
                      "type": "object",
                      "required": ["query"],
                      "additionalProperties": false,
                      "properties": {
                        "query": {"type": "string", "description": "User question or rewritten search query"},
                        "top_k": {"type": "integer", "description": "Maximum number of results, default 3"}
                      }
                    }
                    """);
        } catch (Exception ex) {
            schema = objectMapper.createObjectNode();
        }
        return new AgentToolDefinition(
                INTERNAL_SERVER,
                KNOWLEDGE_SEARCH,
                INTERNAL_SERVER + "." + KNOWLEDGE_SEARCH,
                "Search the local customer-support knowledge base and return grounded evidence chunks.",
                schema,
                "internal"
        );
    }

    private ToolExecution executeKnowledgeSearch(Map<String, Object> arguments) {
        Instant start = Instant.now();
        try {
            McpSchemaValidator.ValidationResult validation = schemaValidator.validate(knowledgeTool.inputSchema(), arguments);
            if (!validation.valid()) {
                return new ToolExecution(false, INTERNAL_SERVER, KNOWLEDGE_SEARCH, objectMapper.nullNode(),
                        "invalid tool arguments: " + String.join("; ", validation.errors()), 0);
            }
            String query = String.valueOf(arguments.getOrDefault("query", ""));
            int topK = parseTopK(arguments.get("top_k"));
            ToolResult<List<SearchResult>> result = knowledgeToolManager.searchWithRewrite(query, topK);
            JsonNode data = objectMapper.valueToTree(Map.of(
                    "results", result.data() == null ? List.of() : result.data(),
                    "reranked", result.reranked(),
                    "cached", result.cached(),
                    "degraded", result.degraded(),
                    "error", result.error() == null ? "" : result.error()
            ));
            return new ToolExecution(result.success(), INTERNAL_SERVER, KNOWLEDGE_SEARCH, data, result.error(), Duration.between(start, Instant.now()).toMillis());
        } catch (Exception ex) {
            return new ToolExecution(false, INTERNAL_SERVER, KNOWLEDGE_SEARCH, objectMapper.getNodeFactory().nullNode(), ex.getMessage(), Duration.between(start, Instant.now()).toMillis());
        }
    }

    private int parseTopK(Object value) {
        if (value instanceof Number number) {
            return Math.max(1, Math.min(10, number.intValue()));
        }
        try {
            return Math.max(1, Math.min(10, Integer.parseInt(String.valueOf(value))));
        } catch (Exception ignored) {
            return 3;
        }
    }

    public record ToolExecution(
            boolean success,
            String server,
            String tool,
            JsonNode result,
            String error,
            long latencyMs
    ) {
    }
}
