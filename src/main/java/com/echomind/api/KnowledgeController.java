package com.echomind.api;

import com.echomind.api.dto.BatchDocInput;
import com.echomind.api.dto.KnowledgeSource;
import com.echomind.config.EchoMindProperties;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.knowledge.SearchResult;
import com.echomind.tool.KnowledgeToolManager;
import com.echomind.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@Tag(name = "Knowledge", description = "Knowledge base search and ingestion")
public class KnowledgeController {

    private final KnowledgeToolManager knowledgeToolManager;
    private final KnowledgeBaseService knowledgeBaseService;
    private final EchoMindProperties properties;
    private final ObjectMapper objectMapper;

    public KnowledgeController(KnowledgeToolManager knowledgeToolManager, KnowledgeBaseService knowledgeBaseService, EchoMindProperties properties, ObjectMapper objectMapper) {
        this.knowledgeToolManager = knowledgeToolManager;
        this.knowledgeBaseService = knowledgeBaseService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/search")
    @Operation(summary = "Search the knowledge base")
    public Map<String, Object> search(
            @Parameter(description = "Search query") @RequestParam String query,
            @Parameter(description = "Maximum number of results") @RequestParam(defaultValue = "5") int topK) {
        ToolResult<List<SearchResult>> result = knowledgeToolManager.searchWithRewrite(query, topK);
        List<SearchResult> usefulKnowledge = usefulKnowledge(result.data());
        List<SearchResult> contextKnowledge = knowledgeBaseService.expandWithNeighbors(usefulKnowledge, properties.getRag().getContextWindow());
        return Map.of(
                "query", query,
                "results", result.data(),
                "sources", usefulKnowledge.stream().map(KnowledgeSource::from).toList(),
                "context_sources", contextKnowledge.stream().map(KnowledgeSource::from).toList(),
                "knowledge_sufficient", knowledgeSufficient(usefulKnowledge),
                "reranked", result.reranked()
        );
    }

    @PostMapping("/knowledge/add")
    @Operation(summary = "Add knowledge documents")
    public Map<String, Object> addKnowledge(@Valid @RequestBody BatchDocInput input) {
        List<Map<String, String>> docs = input.documents().stream()
                .map(d -> Map.of("title", d.title(), "content", d.content(), "source", "api"))
                .toList();
        int added = knowledgeBaseService.addDocuments(docs);
        return Map.of("added_chunks", added, "total_chunks", knowledgeBaseService.docCount());
    }

    @PostMapping(value = "/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a knowledge file")
    public Map<String, Object> uploadKnowledge(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new IllegalArgumentException("file size exceeds 10MB");
        }
        String filename = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String text = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        List<Map<String, String>> docs;
        if (filename.endsWith(".json")) {
            docs = parseJsonDocs(text);
        } else {
            String title = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
            docs = List.of(Map.of("title", title, "content", text, "source", filename));
        }
        int added = knowledgeBaseService.addDocuments(docs);
        return Map.of("message", "file " + filename + " imported", "added_chunks", added, "total_chunks", knowledgeBaseService.docCount());
    }

    @GetMapping("/knowledge/stats")
    @Operation(summary = "Knowledge base stats")
    public Map<String, Object> knowledgeStats() {
        return Map.of("total_chunks", knowledgeBaseService.docCount());
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

    private boolean isFallbackKnowledge(SearchResult result) {
        return result.metadata() != null && Boolean.TRUE.equals(result.metadata().get("fallback"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseJsonDocs(String text) throws Exception {
        List<?> values = objectMapper.readValue(text, List.class);
        List<Map<String, String>> docs = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                Map<String, String> doc = new LinkedHashMap<>();
                Object title = map.containsKey("title") ? map.get("title") : "Untitled";
                Object content = map.containsKey("content") ? map.get("content") : "";
                Object source = map.containsKey("source") ? map.get("source") : "json-upload";
                doc.put("title", String.valueOf(title));
                doc.put("content", String.valueOf(content));
                doc.put("source", String.valueOf(source));
                docs.add(doc);
            }
        }
        return docs;
    }
}
