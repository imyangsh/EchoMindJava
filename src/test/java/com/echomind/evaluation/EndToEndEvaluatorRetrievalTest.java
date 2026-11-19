package com.echomind.evaluation;

import com.echomind.api.dto.EvalRunRequest;
import com.echomind.config.EchoMindProperties;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.knowledge.SearchResult;
import com.echomind.tool.KnowledgeToolManager;
import com.echomind.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class EndToEndEvaluatorRetrievalTest {

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void legacyKeywordLabelsReportHitsWithoutPretendingToMeasureRecall() {
        ObjectMapper objectMapper = new ObjectMapper();
        EchoMindProperties properties = new EchoMindProperties();
        properties.getStorage().setKnowledgePath(tempDir.resolve("knowledge-store.json").toString());
        properties.getEval().setBaselinePath(tempDir.resolve("baseline.json").toString());
        KnowledgeBaseService knowledgeBaseService = new KnowledgeBaseService(properties, noVectorStore(), objectMapper);
        knowledgeBaseService.addDocuments(List.of(Map.of(
                "title", "退款政策",
                "content", "审核通过后，款项将在 5-7 个工作日内退回原支付账户。",
                "source", "refund.md"
        )));
        EndToEndEvaluator evaluator = new EndToEndEvaluator(null, null, null, objectMapper, properties, knowledgeBaseService);

        Map<String, Object> report = evaluator.run(new EvalRunRequest(
                List.of(),
                List.of(new EvalRunRequest.RetrievalCase("退款多久退回原支付账户", "退款政策", "5-7", "", 4)),
                List.of()
        ));

        Map<String, Object> avgScores = (Map<String, Object>) report.get("avg_scores");
        assertThat(avgScores).containsKeys("retrieval_recall_at_k", "retrieval_mrr", "retrieval_hit_rate");
        assertThat(avgScores.get("retrieval_recall_at_k")).isNull();
        assertThat(((Number) avgScores.get("retrieval_hit_rate")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) avgScores.get("retrieval_mrr")).doubleValue()).isEqualTo(1.0);
        assertThat((List<Map<String, Object>>) report.get("results"))
                .anySatisfy(result -> assertThat(result.get("test_id").toString()).startsWith("retrieval_"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentRecallCountsAllAnnotatedDocumentsAndDeduplicatesChunks() {
        KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
        when(knowledge.searchDetailed("退款与配送", 3)).thenReturn(new KnowledgeBaseService.RetrievalBatch(List.of(
                result("chunk-1", "refund", 0.8), result("chunk-2", "refund", 0.7), result("chunk-3", "irrelevant", 0.6)), false, "hash"));
        Map<String, Object> report = evaluator(knowledge, null).run(request(new EvalRunRequest.RetrievalCase(
                "退款与配送", "", "", "", 3, null, List.of("refund", "shipping"), false, null)));
        Map<String, Object> scores = (Map<String, Object>) report.get("avg_scores");
        assertThat(scores).containsEntry("retrieval_recall_at_k", 0.5).containsEntry("retrieval_hit_rate", 1.0)
                .containsEntry("retrieval_mrr", 1.0).containsEntry("retrieval_ndcg_at_k", 0.6131);
        assertThat(report.get("passed")).isEqualTo(0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void chunkRecallAndNdcgUseDistinctRelevantChunksAtTheirReturnedRanks() {
        KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
        when(knowledge.searchDetailed("两个条款", 3)).thenReturn(new KnowledgeBaseService.RetrievalBatch(List.of(
                result("a", "refund", 0.8), result("noise", "other", 0.7), result("b", "refund", 0.6)), false, "hash"));
        Map<String, Object> report = evaluator(knowledge, null).run(request(new EvalRunRequest.RetrievalCase(
                "两个条款", "", "", "", 3, List.of("a", "b"), null, false, null)));
        Map<String, Object> scores = (Map<String, Object>) report.get("avg_scores");
        assertThat(scores).containsEntry("retrieval_recall_at_k", 1.0).containsEntry("retrieval_ndcg_at_k", 0.9197);
        assertThat(report.get("passed")).isEqualTo(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullPipelineUsesUncachedProductionPathAndProvidedHistory() {
        KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
        KnowledgeToolManager manager = mock(KnowledgeToolManager.class);
        List<Map<String, String>> history = List.of(Map.of("role", "user", "content", "退款与配送"));
        SearchResult refund = result("a", "refund", 0.9);
        SearchResult shipping = result("b", "shipping", 0.8);
        when(knowledge.searchDetailed("这两项多久", 2)).thenReturn(new KnowledgeBaseService.RetrievalBatch(List.of(refund), false, "hash"));
        when(manager.searchUncached("这两项多久", 2, history)).thenReturn(new ToolResult<>(
                true, List.of(refund, shipping), "knowledge_search", null, false, 1, true));
        when(manager.stats()).thenReturn(Map.of("knowledge_search", Map.of("llm_calls", 0L, "rerank_latency_ms", 0L)),
                Map.of("knowledge_search", Map.of("llm_calls", 1L, "rerank_latency_ms", 5L)));
        Map<String, Object> report = evaluator(knowledge, manager).run(request(new EvalRunRequest.RetrievalCase(
                "这两项多久", "", "", "", 2, null, List.of("refund", "shipping"), false, history)));
        verify(manager).searchUncached("这两项多久", 2, history);
        Map<String, Object> scores = (Map<String, Object>) report.get("avg_scores");
        assertThat(scores).containsEntry("retrieval_recall_at_k", 0.5).containsEntry("retrieval_pipeline_recall_at_k", 1.0);
        Map<String, Map<String, Object>> modes = (Map<String, Map<String, Object>>) report.get("retrieval_modes");
        assertThat(modes.get("pipeline")).containsEntry("pipeline_counter_delta", Map.of("llm_calls", 1L, "rerank_latency_ms", 5L))
                .containsEntry("cache_hit_rate", 0.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void noAnswerFalseAcceptanceUsesProductionEvidenceAssessorAndFailureIsNotAPass() {
        KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
        when(knowledge.searchDetailed("无答案但误匹配", 4)).thenReturn(new KnowledgeBaseService.RetrievalBatch(
                List.of(result("a", "refund", 0.9)), false, "hash"));
        when(knowledge.searchDetailed("无答案低分", 4)).thenReturn(new KnowledgeBaseService.RetrievalBatch(
                List.of(result("b", "refund", 0.001)), false, "hash"));
        when(knowledge.searchDetailed("检索失败", 4)).thenThrow(new IllegalStateException("offline"));
        Map<String, Object> report = evaluator(knowledge, null).run(new EvalRunRequest(List.of(), List.of(
                noAnswer("无答案但误匹配"), noAnswer("无答案低分"), noAnswer("检索失败")), List.of()));
        Map<String, Object> scores = (Map<String, Object>) report.get("avg_scores");
        assertThat(scores).containsEntry("retrieval_no_answer_false_accept_rate", 0.3333)
                .containsEntry("retrieval_failure_rate", 0.3333);
        assertThat(report.get("passed")).isEqualTo(1L);
        List<Map<String, Object>> results = (List<Map<String, Object>>) report.get("results");
        Map<String, Object> failure = (Map<String, Object>) results.getLast().get("metadata");
        assertThat(failure).containsEntry("success", false).containsEntry("error", "offline");
    }

    @Test
    @SuppressWarnings("unchecked")
    void degradedEmptyBaseRetrievalCannotPassANoAnswerCase() {
        KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
        when(knowledge.searchDetailed("无答案且后端不可用", 4)).thenReturn(new KnowledgeBaseService.RetrievalBatch(
                List.of(), true, "local:hash"));
        Map<String, Object> report = evaluator(knowledge, null).run(request(noAnswer("无答案且后端不可用")));
        assertThat(report.get("passed")).isEqualTo(0L);
        Map<String, Object> scores = (Map<String, Object>) report.get("avg_scores");
        assertThat(scores).containsEntry("retrieval_failure_rate", 1.0);
        List<Map<String, Object>> results = (List<Map<String, Object>>) report.get("results");
        Map<String, Object> metadata = (Map<String, Object>) results.getFirst().get("metadata");
        assertThat(metadata).containsEntry("success", false).containsEntry("degraded", true)
                .containsEntry("error", "retrieval backend degraded with no usable results");
    }

    @Test
    void repeatedRunsPreserveBaselineUntilExplicitPromotion() throws Exception {
        EchoMindProperties properties = properties();
        ObjectMapper mapper = new ObjectMapper();
        KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
        when(knowledge.searchDetailed("问题", 4)).thenReturn(new KnowledgeBaseService.RetrievalBatch(
                List.of(result("a", "refund", 0.8)), false, "hash"));
        EndToEndEvaluator evaluator = new EndToEndEvaluator(null, null, null, mapper, properties, knowledge);
        EvalRunRequest first = request(new EvalRunRequest.RetrievalCase("问题", "", "", "", 4,
                List.of("a"), null, false, null));
        evaluator.run(first);
        Path baseline = Path.of(properties.getEval().getBaselinePath());
        byte[] original = Files.readAllBytes(baseline);
        when(knowledge.searchDetailed("问题", 4)).thenReturn(new KnowledgeBaseService.RetrievalBatch(List.of(), false, "hash"));
        evaluator.run(first);
        assertThat(Files.readAllBytes(baseline)).isEqualTo(original);
        evaluator.run(new EvalRunRequest(first.intentCases(), first.retrievalCases(), first.dialogCases(), true));
        assertThat(Files.readAllBytes(baseline)).isNotEqualTo(original);
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultsIncludeMultipleEvidenceAndNoAnswerAndRunWithoutExternalModels() {
        EchoMindProperties properties = properties();
        KnowledgeBaseService knowledge = new KnowledgeBaseService(properties, noVectorStore(), new ObjectMapper());
        knowledge.init();
        KnowledgeToolManager manager = new KnowledgeToolManager(knowledge,
                (system, user, temperature, maxTokens) -> { throw new IllegalStateException("model unavailable"); }, new ObjectMapper());
        Map<String, Object> report = new EndToEndEvaluator(null, null, null, new ObjectMapper(), properties, knowledge, manager)
                .run(new EvalRunRequest(List.of(), null, List.of()));
        assertThat(report.get("retrieval_pipeline_available")).isEqualTo(true);
        List<Map<String, Object>> results = (List<Map<String, Object>>) report.get("results");
        assertThat(results).anySatisfy(item -> assertThat((Map<String, Object>) item.get("metadata")).containsEntry("relevant_total", 2))
                .anySatisfy(item -> assertThat((Map<String, Object>) item.get("metadata")).containsEntry("no_answer", true));
    }

    @Test
    void oldJsonPayloadsRemainCompatibleAndAmbiguousLabelsAreRejected() throws Exception {
        EvalRunRequest request = new ObjectMapper().readValue("""
                {"intentCases":[],"retrievalCases":[{"query":"退款","expectedTitle":"退款","topK":4}],"dialogCases":[]}
                """, EvalRunRequest.class);
        assertThat(request.updateBaseline()).isNull();
        assertThat(request.retrievalCases().getFirst().relevantChunkIds()).isNull();
        assertThat(request.retrievalCases().getFirst().history()).isNull();
        assertThatThrownBy(() -> evaluator(mock(KnowledgeBaseService.class), null).run(request(
                new EvalRunRequest.RetrievalCase("退款", "", "", "", 4, List.of("a"), List.of("doc"), false, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("分母");
    }

    private EvalRunRequest request(EvalRunRequest.RetrievalCase retrieval) {
        return new EvalRunRequest(List.of(), List.of(retrieval), List.of());
    }

    private EvalRunRequest.RetrievalCase noAnswer(String query) {
        return new EvalRunRequest.RetrievalCase(query, "", "", "", 4, null, null, true, null);
    }

    private SearchResult result(String chunkId, String documentId, double score) {
        return new SearchResult(chunkId, documentId, "退款政策条款", score, 0,
                Map.of("source", "policy.md", "document_id", documentId));
    }

    private EndToEndEvaluator evaluator(KnowledgeBaseService knowledge, KnowledgeToolManager manager) {
        return new EndToEndEvaluator(null, null, null, new ObjectMapper(), properties(), knowledge, manager);
    }

    private EchoMindProperties properties() {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getStorage().setKnowledgePath(tempDir.resolve("knowledge-store.json").toString());
        properties.getEval().setBaselinePath(tempDir.resolve("baseline.json").toString());
        return properties;
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
