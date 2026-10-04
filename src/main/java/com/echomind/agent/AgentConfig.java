package com.echomind.agent;

import com.echomind.llm.LlmGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
public class AgentConfig {

    @Bean
    Map<AgentType, List<BaseAgent>> agentPool(
            LlmGateway llmGateway,
            AgentToolService toolService
    ) {
        return Map.of(
                AgentType.GENERAL, List.of(new GeneralAgent(llmGateway, toolService)),
                AgentType.TECHNICAL, List.of(new TechnicalAgent(llmGateway, toolService)),
                AgentType.BILLING, List.of(new BillingAgent(llmGateway, toolService)),
                AgentType.ESCALATION, List.of(new EscalationAgent(llmGateway, toolService))
        );
    }
}
