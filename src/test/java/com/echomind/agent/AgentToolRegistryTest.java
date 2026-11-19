package com.echomind.agent;

import com.echomind.config.EchoMindProperties;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.llm.LlmGateway;
import com.echomind.mcp.McpClientManager;
import com.echomind.tool.KnowledgeToolManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AgentToolRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void internalKnowledgeToolValidatesSchemaBeforeInvokingRetrieval() {
        ObjectMapper objectMapper = new ObjectMapper();
        KnowledgeToolManager knowledge = mock(KnowledgeToolManager.class);
        AgentToolRegistry registry = new AgentToolRegistry(null, knowledge, objectMapper);
        AgentToolRegistry.ToolExecution result = registry.execute("internal", "knowledge_search",
                Map.of("query", 123, "hidden_argument", "bad"));
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("query must be string", "hidden_argument is not allowed");
        verifyNoInteractions(knowledge);
    }

    @Test
    void exposesKnowledgeSearchAsInternalAgentTool() {
        ObjectMapper objectMapper = new ObjectMapper();
        EchoMindProperties properties = new EchoMindProperties();
        properties.getStorage().setKnowledgePath(tempDir.resolve("knowledge-store.json").toString());
        KnowledgeBaseService knowledgeBaseService = new KnowledgeBaseService(properties, noVectorStore(), objectMapper);
        knowledgeBaseService.addDocuments(List.of(Map.of(
                "title", "Refund Policy",
                "content", "Refund review takes one to three business days.",
                "source", "test"
        )));
        LlmGateway llm = (systemPrompt, userPrompt, temperature, maxTokens) -> "not-json";
        KnowledgeToolManager knowledgeToolManager = new KnowledgeToolManager(knowledgeBaseService, llm, objectMapper);
        McpClientManager mcpClientManager = new McpClientManager(properties, objectMapper);
        AgentToolRegistry registry = new AgentToolRegistry(mcpClientManager, knowledgeToolManager, objectMapper);

        assertThat(registry.listAgentTools()).anySatisfy(tool -> {
            assertThat(tool.server()).isEqualTo(AgentToolRegistry.INTERNAL_SERVER);
            assertThat(tool.name()).isEqualTo(AgentToolRegistry.KNOWLEDGE_SEARCH);
        });

        AgentToolRegistry.ToolExecution result = registry.execute(
                AgentToolRegistry.INTERNAL_SERVER,
                AgentToolRegistry.KNOWLEDGE_SEARCH,
                Map.of("query", "refund review")
        );

        assertThat(result.success()).isTrue();
        assertThat(result.result().path("results")).isNotEmpty();
    }

    private ObjectProvider<EmbeddingStore<TextSegment>> noVectorStore() {
        return new ObjectProvider<>() {
            @Override
            public EmbeddingStore<TextSegment> getObject(Object... args) {
                return null;
            }

            @Override
            public EmbeddingStore<TextSegment> getIfAvailable() {
                return null;
            }

            @Override
            public EmbeddingStore<TextSegment> getIfUnique() {
                return null;
            }

            @Override
            public EmbeddingStore<TextSegment> getObject() {
                return null;
            }

            @Override
            public Stream<EmbeddingStore<TextSegment>> stream() {
                return Stream.empty();
            }

            @Override
            public Stream<EmbeddingStore<TextSegment>> orderedStream() {
                return Stream.empty();
            }
        };
    }
}
