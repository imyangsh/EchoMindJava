package com.echomind.agent;

import com.echomind.llm.LlmGateway;

import java.time.Duration;
import java.time.Instant;

public abstract class BaseAgent {

    private final LlmGateway llmGateway;
    private final AgentToolService toolService;
    private final AgentStats stats = new AgentStats();

    protected BaseAgent(LlmGateway llmGateway, AgentToolService toolService) {
        this.llmGateway = llmGateway;
        this.toolService = toolService;
    }

    public abstract AgentType type();

    protected abstract String systemPrompt();

    public AgentResponse handle(AgentRequest request) {
        Instant start = Instant.now();
        try {
            String prompt = buildPrompt(request);
            AgentToolService.GenerationResult generation = toolService.generate(type(), systemPrompt(), request, prompt, llmGateway);
            long latency = Duration.between(start, Instant.now()).toMillis();
            boolean escalate = needsEscalation(generation.content());
            stats.record(true, latency);
            return new AgentResponse(type(), generation.content(), true, 1.0, latency, escalate, generation.toolCalls());
        } catch (Exception ex) {
            long latency = Duration.between(start, Instant.now()).toMillis();
            stats.record(false, latency);
            return new AgentResponse(type(), "抱歉，处理您的请求时出现问题，请稍后重试。", false, 0.0, latency, false);
        }
    }

    public AgentStats stats() {
        return stats;
    }

    private String buildPrompt(AgentRequest request) {
        StringBuilder prompt = new StringBuilder();
        if (request.context() != null && !request.context().isBlank()) {
            prompt.append("[Context]\n").append(request.context()).append("\n\n");
        }
        prompt.append("[User]\n").append(request.message());
        return prompt.toString();
    }

    private boolean needsEscalation(String content) {
        String text = content == null ? "" : content.toLowerCase();
        return text.contains("转人工")
                || text.contains("人工客服")
                || text.contains("escalate")
                || text.contains("specialist")
                || text.contains("无法处理");
    }
}
