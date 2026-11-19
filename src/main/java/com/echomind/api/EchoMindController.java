package com.echomind.api;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AnswerVerifier;
import com.echomind.api.dto.ChatRequest;
import com.echomind.api.dto.ChatResponse;
import com.echomind.config.EchoMindProperties;
import com.echomind.knowledge.KnowledgeAssessor;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.memory.MemoryManager;
import com.echomind.security.ConversationAccessService;
import com.echomind.security.UserKeyAuthenticator;
import com.echomind.tool.KnowledgeToolManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "Chat", description = "Conversation and health endpoints")
public class EchoMindController {
    private final AgentOrchestrator orchestrator;
    private final ChatPipeline pipeline;
    private final ConversationAccessService conversationAccess;

    @Autowired
    public EchoMindController(AgentOrchestrator orchestrator, ChatPipeline pipeline,
                              ObjectProvider<ConversationAccessService> accessProvider) {
        this(orchestrator, pipeline, accessProvider.getIfAvailable());
    }

    public EchoMindController(AgentOrchestrator orchestrator, ChatPipeline pipeline,
                              ConversationAccessService conversationAccess) {
        this.orchestrator = orchestrator;
        this.pipeline = pipeline;
        this.conversationAccess = conversationAccess;
    }

    public EchoMindController(AgentOrchestrator orchestrator, ChatPipeline pipeline) {
        this(orchestrator, pipeline, (ConversationAccessService) null);
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
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream().noneMatch(authority -> authority.getAuthority().equals("ROLE_USER"))
                || !UserKeyAuthenticator.validUserId(authentication.getName())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "valid user authentication required");
        }
        String userId = authentication.getName();
        if (request.userId() != null && !request.userId().isBlank() && !request.userId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "request user does not match authenticated user");
        }
        if (conversationAccess == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "conversation access unavailable");
        }
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString() : request.conversationId();
        ChatRequest bound = new ChatRequest(request.message(), userId, conversationId);
        try (ConversationAccessService.Lease ignored = conversationAccess.acquire(userId, conversationId)) {
            return pipeline.chat(bound);
        }
    }

    /** Java-only compatibility entry point; HTTP always uses the authenticated overload above. */
    public ChatResponse chat(ChatRequest request) {
        return pipeline.chat(request);
    }
}
