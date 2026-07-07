package com.echomind.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

public interface McpClient extends AutoCloseable {

    String serverName();

    List<McpToolInfo> listTools();

    JsonNode callTool(String toolName, Map<String, Object> arguments);

    Map<String, Object> summary();

    @Override
    void close();
}
