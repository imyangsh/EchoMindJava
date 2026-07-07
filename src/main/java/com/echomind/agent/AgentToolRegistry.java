package com.echomind.agent;

import com.echomind.knowledge.SearchResult;
import com.echomind.mcp.McpCallResult;
import com.echomind.mcp.McpClientManager;
import com.echomind.mcp.McpToolInfo;
import com.echomind.tool.KnowledgeToolManager;
import com.echomind.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AgentToolRegistry {

    public static final String INTERNAL_SERVER = "internal";
    public static final String KNOWLEDGE_SEARCH = "knowledge_search";

    private final McpClientManager mcpClientManager;
    private final KnowledgeToolManager knowledgeToolManager;
    private final ObjectMapper objectMapper;

    public AgentToolRegistry(McpClientManager mcpClientManager, KnowledgeToolManager knowledgeToolManager, ObjectMapper objectMapper) {
        this.mcpClientManager = mcpClientManager;
        this.knowledgeToolManager = knowledgeToolManager;
        this.objectMapper = objectMapper;
    }

    public List<AgentToolDefinition> listAgentTools() {
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
        tools.add(knowledgeSearchTool());
        return tools;
    }

    public ToolExecution execute(String server, String tool, Map<String, Object> arguments) {
        if (INTERNAL_SERVER.equals(server) && KNOWLEDGE_SEARCH.equals(tool)) {
            return executeKnowledgeSearch(arguments);
        }
        if (mcpClientManager == null) {
            return new ToolExecution(false, server, tool, objectMapper.getNodeFactory().nullNode(), "MCP is unavailable", 0);
        }
        McpCallResult result = mcpClientManager.callTool(server, tool, arguments);
        return new ToolExecution(result.success(), result.serverName(), result.toolName(), result.result(), result.error(), result.latencyMs());
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
            String query = String.valueOf(arguments.getOrDefault("query", ""));
            int topK = parseTopK(arguments.get("top_k"));
            ToolResult<List<SearchResult>> result = knowledgeToolManager.searchWithRewrite(query, topK);
            JsonNode data = objectMapper.valueToTree(Map.of(
                    "results", result.data() == null ? List.of() : result.data(),
                    "reranked", result.reranked(),
                    "cached", result.cached(),
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
