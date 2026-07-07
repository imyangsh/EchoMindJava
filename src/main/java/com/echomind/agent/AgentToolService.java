package com.echomind.agent;

import com.echomind.llm.LlmGateway;
import com.echomind.skill.SkillManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AgentToolService {

    private final AgentToolRegistry toolRegistry;
    private final SkillManager skillManager;
    private final ObjectMapper objectMapper;

    public AgentToolService(AgentToolRegistry toolRegistry, SkillManager skillManager, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.skillManager = skillManager;
        this.objectMapper = objectMapper;
    }

    public GenerationResult generate(
            AgentType agentType,
            String systemPrompt,
            AgentRequest request,
            String basePrompt,
            LlmGateway llmGateway
    ) {
        String system = buildSystemPrompt(agentType, systemPrompt, request);
        List<AgentToolDefinition> tools = toolRegistry.listAgentTools();
        if (tools.isEmpty()) {
            return new GenerationResult(llmGateway.chat(system, basePrompt, 0.2, 1024), List.of());
        }
        String toolCatalog = buildToolCatalog(tools);
        String prompt = basePrompt;
        List<String> executed = new ArrayList<>();
        List<ToolCallTrace> traces = new ArrayList<>();
        int maxIterations = toolRegistry.maxIterations();
        for (int i = 0; i < maxIterations; i++) {
            ToolDecision decision = chooseTool(agentType, request, prompt, toolCatalog, llmGateway);
            if (!decision.useTool()) {
                break;
            }
            String key = decision.server() + "." + decision.tool() + ":" + safeJson(decision.arguments());
            if (executed.contains(key)) {
                prompt += "\n\n[Tool call skipped]\nReason: model requested the same tool call twice: " + key;
                break;
            }
            executed.add(key);
            AgentToolRegistry.ToolExecution result = toolRegistry.execute(decision.server(), decision.tool(), decision.arguments());
            traces.add(new ToolCallTrace(
                    decision.server(),
                    decision.tool(),
                    decision.arguments(),
                    result.success(),
                    result.error(),
                    result.latencyMs(),
                    decision.reason()
            ));
            prompt += "\n\n[Tool call result]\n"
                    + "Tool: " + decision.server() + "." + decision.tool() + "\n"
                    + "Reason: " + decision.reason() + "\n"
                    + "Arguments: " + safeJson(decision.arguments()) + "\n"
                    + "Success: " + result.success() + "\n"
                    + "LatencyMs: " + result.latencyMs() + "\n"
                    + "Error: " + (result.error() == null ? "" : result.error()) + "\n"
                    + "Result: " + truncate(safeJson(result.result()), 4000);
            if (!result.success()) {
                break;
            }
        }
        return new GenerationResult(llmGateway.chat(system, prompt, 0.2, 1024), traces);
    }

    private ToolDecision chooseTool(AgentType agentType, AgentRequest request, String prompt, String toolCatalog, LlmGateway llmGateway) {
        String routerPrompt = """
                You are EchoMind's tool router. Decide whether the current user request needs a tool call.
                Call a tool only when:
                - the request needs external systems, realtime data, files, APIs, MCP capabilities, or additional knowledge-base evidence;
                - the tool description and input_schema clearly match the user need;
                - the current context is insufficient for a grounded direct answer.
                Do not call tools for casual chat or when the provided context already answers the question.

                Agent type: %s
                User message: %s

                Current context:
                %s

                Available tools JSON:
                %s

                Return JSON only.
                No tool:
                {"use_tool":false,"reason":"..."}

                Tool call:
                {"use_tool":true,"server":"serverName","tool":"toolName","arguments":{},"reason":"..."}
                """.formatted(agentType.name().toLowerCase(), request.message(), truncate(prompt, 3000), toolCatalog);
        try {
            String raw = llmGateway.chat("Return parseable JSON only.", routerPrompt, 0.0, 512);
            JsonNode json = objectMapper.readTree(sliceJsonObject(raw));
            boolean useTool = json.path("use_tool").asBoolean(json.path("useTool").asBoolean(false));
            String server = json.path("server").asText("");
            String tool = json.path("tool").asText("");
            String reason = json.path("reason").asText("");
            Map<String, Object> arguments = json.has("arguments") && json.get("arguments").isObject()
                    ? objectMapper.convertValue(json.get("arguments"), new TypeReference<>() {
                    })
                    : Map.of();
            if (!useTool || server.isBlank() || tool.isBlank()) {
                return ToolDecision.skip(reason);
            }
            return new ToolDecision(true, server, tool, arguments, reason);
        } catch (Exception ex) {
            return ToolDecision.skip("tool routing failed: " + ex.getMessage());
        }
    }

    private String buildToolCatalog(List<AgentToolDefinition> tools) {
        List<Map<String, Object>> summaries = tools.stream().map(AgentToolDefinition::summary).toList();
        return truncate(safeJson(summaries), toolRegistry.maxToolPromptChars());
    }

    private String buildSystemPrompt(AgentType agentType, String systemPrompt, AgentRequest request) {
        if (skillManager == null) {
            return systemPrompt;
        }
        String skillPrompt = skillManager.promptFor(request.message(), agentType.name().toLowerCase());
        if (skillPrompt == null || skillPrompt.isBlank()) {
            return systemPrompt;
        }
        return systemPrompt + "\n\n[Dynamic Skills]\n" + skillPrompt;
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String sliceJsonObject(String raw) {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : "{}";
    }

    private String truncate(String value, int max) {
        String text = value == null ? "" : value;
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max)).stripTrailing() + "\n...";
    }

    private record ToolDecision(boolean useTool, String server, String tool, Map<String, Object> arguments, String reason) {
        static ToolDecision skip(String reason) {
            return new ToolDecision(false, "", "", Map.of(), reason == null ? "" : reason);
        }
    }

    public record GenerationResult(String content, List<ToolCallTrace> toolCalls) {
        public GenerationResult {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }
    }
}
