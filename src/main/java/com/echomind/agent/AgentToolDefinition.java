package com.echomind.agent;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record AgentToolDefinition(
        String server,
        String name,
        String qualifiedName,
        String description,
        JsonNode inputSchema,
        String kind
) {
    public Map<String, Object> summary() {
        return Map.of(
                "server", server,
                "name", name,
                "qualified_name", qualifiedName,
                "description", description == null ? "" : description,
                "input_schema", inputSchema,
                "kind", kind
        );
    }
}
