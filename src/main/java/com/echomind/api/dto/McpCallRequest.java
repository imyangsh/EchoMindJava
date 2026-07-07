package com.echomind.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "MCP 工具调用请求")
public record McpCallRequest(
        @Schema(description = "工具参数，必须符合 MCP tool 的 inputSchema")
        Map<String, Object> arguments
) {
    public Map<String, Object> argumentsOrEmpty() {
        return arguments == null ? Map.of() : arguments;
    }
}
