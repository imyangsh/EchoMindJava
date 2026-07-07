package com.echomind.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public record McpCallResult(
        boolean success,
        String serverName,
        String toolName,
        JsonNode result,
        String error,
        long latencyMs
) {
}
