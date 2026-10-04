package com.echomind.api;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AnswerVerifier;
import com.echomind.api.dto.ChatRequest;
import com.echomind.api.dto.ChatResponse;
import com.echomind.config.EchoMindProperties;
import com.echomind.knowledge.KnowledgeAssessor;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.memory.MemoryManager;
import com.echomind.tool.KnowledgeToolManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "Chat", description = "Conversation and health endpoints")
public class EchoMindController {
    private final AgentOrchestrator orchestrator;
    private final ChatPipeline pipeline;

    @Autowired
    public EchoMindController(AgentOrchestrator orchestrator, ChatPipeline pipeline) {
        this.orchestrator = orchestrator;
        this.pipeline = pipeline;
    }

    /** Retained for callers that construct the controller without a Spring context. */
    public EchoMindController(AgentOrchestrator orchestrator, MemoryManager memoryManager,
                              KnowledgeToolManager knowledgeToolManager, KnowledgeBaseService knowledgeBaseService,
                              KnowledgeAssessor knowledgeAssessor, AnswerVerifier answerVerifier,
                              EchoMindProperties properties, ObjectMapper objectMapper) {
        this(orchestrator, new ChatPipeline(orchestrator, memoryManager, knowledgeToolManager, knowledgeBaseService,
                knowledgeAssessor, answerVerifier, properties, objectMapper));
    }

    @GetMapping("/health")
    @Operation(summary = "Health check")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "agents", orchestrator.stats());
    }

    @PostMapping("/chat")
    @Operation(summary = "Run a customer-support conversation turn")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return pipeline.chat(request);
    }
}
