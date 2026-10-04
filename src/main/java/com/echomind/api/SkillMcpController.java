package com.echomind.api;

import com.echomind.api.dto.McpCallRequest;
import com.echomind.mcp.McpClientManager;
import com.echomind.skill.SkillManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "Skills and MCP", description = "Runtime skills and MCP tool endpoints")
public class SkillMcpController {

    private final SkillManager skillManager;
    private final McpClientManager mcpClientManager;

    public SkillMcpController(SkillManager skillManager, McpClientManager mcpClientManager) {
        this.skillManager = skillManager;
        this.mcpClientManager = mcpClientManager;
    }

    @GetMapping("/skills")
    @Operation(summary = "List loaded skills")
    public Map<String, Object> skills() {
        return skillManager.summary();
    }

    @PostMapping("/skills/reload")
    @Operation(summary = "Reload skills")
    public Map<String, Object> reloadSkills() {
        return skillManager.reloadReport().summary();
    }

    @GetMapping("/mcp")
    @Operation(summary = "MCP status")
    public Map<String, Object> mcpSummary() {
        return mcpClientManager.summary();
    }

    @PostMapping("/mcp/reload")
    @Operation(summary = "Reload MCP configuration")
    public Map<String, Object> reloadMcp() {
        return mcpClientManager.reload();
    }

    @GetMapping("/mcp/tools")
    @Operation(summary = "List MCP tools")
    public Map<String, Object> mcpTools() {
        return Map.of("tools", mcpClientManager.listAllToolSummaries());
    }

    @PostMapping(value = "/mcp/tools/{serverName}/{toolName}/call", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Call an MCP tool")
    public Map<String, Object> callMcpTool(
            @PathVariable String serverName,
            @PathVariable String toolName,
            @RequestBody(required = false) McpCallRequest request
    ) {
        var result = mcpClientManager.callToolManually(serverName, toolName, request == null ? Map.of() : request.argumentsOrEmpty());
        return Map.of(
                "success", result.success(),
                "server", result.serverName(),
                "tool", result.toolName(),
                "result", result.result(),
                "error", result.error() == null ? "" : result.error(),
                "latency_ms", result.latencyMs()
        );
    }
}
