package com.echomind.evaluation;

import com.echomind.api.dto.EvalRunRequest;
import com.echomind.config.EchoMindProperties;
import com.echomind.knowledge.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class EndToEndEvaluatorRetrievalTest {

    @TempDir
    Path tempDir;

    @Test
    @SuppressWarnings("unchecked")
    void reportContainsRetrievalMetrics() {
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
        assertThat(((Number) avgScores.get("retrieval_recall_at_k")).doubleValue()).isEqualTo(1.0);
        assertThat((List<Map<String, Object>>) report.get("results"))
                .anySatisfy(result -> assertThat(result.get("test_id").toString()).startsWith("retrieval_"));
    }

    private ObjectProvider<VectorStore> noVectorStore() {
        return new ObjectProvider<>() {
            @Override
            public VectorStore getObject(Object... args) {
                return null;
            }

            @Override
            public VectorStore getIfAvailable() {
                return null;
            }

            @Override
            public VectorStore getIfUnique() {
                return null;
            }

            @Override
            public VectorStore getObject() {
                return null;
            }

            @Override
            public Stream<VectorStore> stream() {
                return Stream.empty();
            }

            @Override
            public Stream<VectorStore> orderedStream() {
                return Stream.empty();
            }
        };
    }
}
