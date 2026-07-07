package com.echomind.knowledge;

import com.echomind.config.EchoMindProperties;
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

class KnowledgeBaseServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void chunksKeepHeadingSourceParagraphAndNeighborMetadata() {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getStorage().setKnowledgePath(tempDir.resolve("knowledge-store.json").toString());
        KnowledgeBaseService service = new KnowledgeBaseService(properties, noVectorStore(), new ObjectMapper());

        int added = service.addDocuments(List.of(Map.of(
                "title", "Support Handbook",
                "source", "support.md",
                "content", """
                        # Technical

                        Login token reset failures happen when the user's verification code expires before submission. Ask for the timestamp, device model, app version, and whether a VPN or proxy is enabled.

                        If the customer sees error 500 during login, confirm whether the incident affects only one account or multiple accounts. Collect request id, approximate time, and screenshot before escalating to technical support.

                        Cache cleanup can solve local startup crashes. Ask the user to clear cache, restart the application, and retry on a stable network before collecting logs.

                        Repeated authentication failures can lock the account temporarily. The support agent should verify ownership and guide the user through phone or email recovery.

                        # Billing

                        Refund review usually takes one to three business days after submission. Approved refunds return to the original payment account in five to seven business days.
                        """
        )));

        assertThat(added).isGreaterThan(1);

        List<SearchResult> results = service.search("login token error 500 request id", 5);
        assertThat(results).isNotEmpty();
        SearchResult top = results.getFirst();
        assertThat(top.metadata())
                .containsEntry("source", "support.md")
                .containsEntry("splitter", "heading-paragraph-v1");
        assertThat(top.metadata().get("heading_path").toString()).contains("Support Handbook > Technical");
        assertThat(top.metadata()).containsKeys("paragraph_start", "paragraph_end", "previous_chunk_id", "next_chunk_id");
        assertThat(results.stream().map(SearchResult::metadata).map(metadata -> metadata.get("next_chunk_id").toString()))
                .anyMatch(value -> !value.isBlank());

        List<SearchResult> focused = service.search("error 500 request id", 1);
        List<SearchResult> expanded = service.expandWithNeighbors(focused, 1);
        assertThat(expanded).hasSizeGreaterThan(focused.size());
        assertThat(expanded).extracting(SearchResult::id).contains(focused.getFirst().id());
        assertThat(expanded)
                .anySatisfy(result -> {
                    if (!result.id().equals(focused.getFirst().id())) {
                        assertThat(result.metadata()).containsEntry("context_expansion", true);
                        assertThat(result.metadata()).containsEntry("anchor_chunk_id", focused.getFirst().id());
                    }
                });
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
