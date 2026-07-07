package com.echomind.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record McpToolInfo(
        String serverName,
        String name,
        String qualifiedName,
        String description,
        JsonNode inputSchema
) {
    public Map<String, Object> summary() {
        return Map.of(
                "server", serverName,
                "name", name,
                "qualified_name", qualifiedName,
                "description", description == null ? "" : description,
                "input_schema", inputSchema
        );
    }
}
