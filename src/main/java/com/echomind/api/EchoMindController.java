package com.echomind.api;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AnswerVerifier;
import com.echomind.agent.OrchestratorResult;
import com.echomind.api.dto.ChatRequest;
import com.echomind.api.dto.ChatResponse;
import com.echomind.api.dto.KnowledgeSource;
import com.echomind.config.EchoMindProperties;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.knowledge.SearchResult;
import com.echomind.memory.MemoryContext;
import com.echomind.memory.MemoryManager;
import com.echomind.memory.MessageRole;
import com.echomind.tool.KnowledgeToolManager;
import com.echomind.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "Chat", description = "Conversation and health endpoints")
public class EchoMindController {

    private final AgentOrchestrator orchestrator;
    private final MemoryManager memoryManager;
    private final KnowledgeToolManager knowledgeToolManager;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AnswerVerifier answerVerifier;
    private final EchoMindProperties properties;
    private final ObjectMapper objectMapper;

    public EchoMindController(
            AgentOrchestrator orchestrator,
            MemoryManager memoryManager,
            KnowledgeToolManager knowledgeToolManager,
            KnowledgeBaseService knowledgeBaseService,
            AnswerVerifier answerVerifier,
            EchoMindProperties properties,
            ObjectMapper objectMapper
    ) {
        this.orchestrator = orchestrator;
        this.memoryManager = memoryManager;
        this.knowledgeToolManager = knowledgeToolManager;
        this.knowledgeBaseService = knowledgeBaseService;
        this.answerVerifier = answerVerifier;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/health")
    @Operation(summary = "Health check")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "agents", orchestrator.stats());
    }

    @PostMapping("/chat")
    @Operation(summary = "Run a customer-support conversation turn")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String userId = request.userIdOrDefault();
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();
        MemoryContext memoryContext = memoryManager.getContext(userId, conversationId, request.message());
        String memoryText = memoryContext.toPromptText(objectMapper);

        boolean requiresKnowledge = shouldUseKnowledge(request.message());
        ToolResult<List<SearchResult>> knowledge = requiresKnowledge
                ? knowledgeToolManager.searchWithRewrite(request.message(), 3)
                : new ToolResult<>(true, List.of(), "knowledge_search", null, false, 0, false);
        List<SearchResult> usefulKnowledge = usefulKnowledge(knowledge.data());
        List<SearchResult> contextKnowledge = knowledgeBaseService.expandWithNeighbors(usefulKnowledge, properties.getRag().getContextWindow());
        List<KnowledgeSource> knowledgeSources = contextKnowledge.stream().map(KnowledgeSource::from).toList();
        boolean knowledgeSufficient = !requiresKnowledge || knowledgeSufficient(usefulKnowledge);
        String knowledgeWarning = knowledgeWarning(requiresKnowledge, usefulKnowledge, knowledgeSufficient);
        String knowledgeText = buildKnowledgeContext(contextKnowledge.isEmpty() ? knowledge.data() : contextKnowledge);
        String fullContext = join(join(memoryText, knowledgeText), knowledgeWarningPrompt(knowledgeWarning));

        List<Map<String, String>> history = memoryContext.recentMessages().stream()
                .skip(Math.max(0, memoryContext.recentMessages().size() - 5))
                .map(m -> Map.of("role", m.role().name().toLowerCase(), "content", m.content()))
                .toList();
        OrchestratorResult result = orchestrator.run(AgentRequest.of(request.message(), userId, conversationId, fullContext, history));
        AnswerVerifier.VerificationResult verification = answerVerifier.verify(request.message(), result.response(), fullContext);
        boolean escalated = result.escalated() || verification.needEscalation() || (requiresKnowledge && !knowledgeSufficient);
        List<String> toolErrors = result.toolCalls().stream()
                .filter(call -> !call.success() && !call.error().isBlank())
                .map(call -> call.server() + "." + call.tool() + ": " + call.error())
                .toList();
        long toolLatencyMs = result.toolCalls().stream().mapToLong(call -> call.latencyMs()).sum();

        memoryManager.addMessage(userId, conversationId, MessageRole.USER, request.message());
        memoryManager.addMessage(userId, conversationId, MessageRole.ASSISTANT, result.response());
        memoryManager.updateProfile(userId, conversationId);

        return new ChatResponse(
                conversationId,
                result.response(),
                result.intent() == null ? "other" : result.intent().name().toLowerCase(),
                result.agentType().name().toLowerCase(),
                escalated,
                result.latencyMs(),
                !knowledgeSources.isEmpty(),
                knowledgeSources,
                knowledgeSufficient,
                knowledgeWarning,
                verification.pass(),
                verification.grounded() && knowledgeSufficient,
                verification.reason(),
                !result.toolCalls().isEmpty(),
                result.toolCalls(),
                toolErrors,
                toolLatencyMs
        );
    }

    private List<SearchResult> usefulKnowledge(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream()
                .filter(result -> result != null && !isFallbackKnowledge(result) && result.score() > 0.0)
                .toList();
    }

    private boolean knowledgeSufficient(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        double minScore = Math.max(0.0, properties.getRag().getMinRelevantScore());
        int minSources = Math.max(1, properties.getRag().getMinSources());
        long relevant = results.stream().filter(result -> result.score() >= minScore).count();
        return relevant >= minSources;
    }

    private String knowledgeWarning(boolean requiresKnowledge, List<SearchResult> results, boolean sufficient) {
        if (!requiresKnowledge || sufficient) {
            return "";
        }
        double topScore = results == null || results.isEmpty()
                ? 0.0
                : results.stream().mapToDouble(SearchResult::score).max().orElse(0.0);
        return "Knowledge evidence is insufficient for a grounded answer. top_score="
                + topScore
                + ", min_relevant_score="
                + properties.getRag().getMinRelevantScore()
                + ". The assistant should avoid unsupported claims and suggest human escalation when needed.";
    }

    private String knowledgeWarningPrompt(String warning) {
        if (warning == null || warning.isBlank()) {
            return "";
        }
        return "[Knowledge Safety]\n" + warning + "\nDo not invent policy details that are not supported by the retrieved knowledge.";
    }

    private boolean isFallbackKnowledge(SearchResult result) {
        return result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("fallback"));
    }

    private boolean shouldUseKnowledge(String message) {
        String msg = message == null ? "" : message.toLowerCase();
        if (List.of("你好", "您好", "hi", "hello", "hey").contains(msg.trim())) {
            return false;
        }
        return msg.length() >= 4 || msg.contains("退款") || msg.contains("订单") || msg.contains("登录") || msg.contains("报错");
    }

    private String buildKnowledgeContext(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add("[Knowledge Results]");
        for (int i = 0; i < results.size(); i++) {
            SearchResult item = results.get(i);
            Object source = item.metadata() == null ? "" : item.metadata().getOrDefault("source", "");
            Object headingPath = item.metadata() == null ? "" : item.metadata().getOrDefault("heading_path", "");
            parts.add((i + 1) + ". Title: " + item.title()
                    + "\n   Source: " + source
                    + "\n   Section: " + headingPath
                    + "\n   Score: " + item.score()
                    + "\n   Content: " + item.content());
        }
        parts.add("Answer using the knowledge above first. If it is insufficient, say so and recommend human escalation.");
        return String.join("\n", parts);
    }

    private String join(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }
        return left + "\n\n" + right;
    }
}
