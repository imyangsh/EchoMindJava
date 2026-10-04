package com.echomind.api;

import com.echomind.api.dto.BatchDocInput;
import com.echomind.api.dto.KnowledgeSource;
import com.echomind.config.EchoMindProperties;
import com.echomind.knowledge.KnowledgeAssessor;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.knowledge.SearchResult;
import com.echomind.tool.KnowledgeToolManager;
import com.echomind.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@Validated
@Tag(name = "Knowledge", description = "Knowledge base search and ingestion")
public class KnowledgeController {

    /** 允许上传的知识文件扩展名白名单。 */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("md", "txt", "json");

    private final KnowledgeToolManager knowledgeToolManager;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeAssessor knowledgeAssessor;
    private final EchoMindProperties properties;
    private final ObjectMapper objectMapper;

    public KnowledgeController(KnowledgeToolManager knowledgeToolManager, KnowledgeBaseService knowledgeBaseService,
                               KnowledgeAssessor knowledgeAssessor, EchoMindProperties properties, ObjectMapper objectMapper) {
        this.knowledgeToolManager = knowledgeToolManager;
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeAssessor = knowledgeAssessor;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/search")
    @Operation(summary = "Search the knowledge base")
    public Map<String, Object> search(
            @Parameter(description = "Search query") @RequestParam @NotBlank @Size(max = 1000) String query,
            @Parameter(description = "Maximum number of results") @RequestParam(defaultValue = "5") @Min(1) @Max(20) int topK) {
        ToolResult<List<SearchResult>> result = knowledgeToolManager.searchWithRewrite(query, topK);
        List<SearchResult> usefulKnowledge = result.success()
                ? knowledgeAssessor.usefulKnowledge(result.data()) : List.of();
        List<SearchResult> contextKnowledge = knowledgeBaseService.expandWithNeighbors(usefulKnowledge, properties.getRag().getContextWindow());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("results", result.data());
        response.put("sources", usefulKnowledge.stream().map(KnowledgeSource::from).toList());
        response.put("context_sources", contextKnowledge.stream().map(KnowledgeSource::from).toList());
        response.put("knowledge_sufficient", result.success() && knowledgeAssessor.knowledgeSufficient(usefulKnowledge));
        response.put("success", result.success());
        response.put("degraded", result.degraded());
        response.put("error", result.error() == null ? "" : result.error());
        response.put("cached", result.cached());
        response.put("latency_ms", result.latencyMs());
        response.put("reranked", result.reranked());
        return response;
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
    @Operation(summary = "Upload a knowledge file (.md / .txt / .json)")
    public Map<String, Object> uploadKnowledge(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new IllegalArgumentException("file size exceeds 10MB");
        }
        //文件名规范化：去掉路径部分并清洗特殊字符，
        //避免攻击者可控的文件名注入换行伪造 prompt 段落边界，或携带路径穿越
        String rawName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String filename = sanitizeFilename(rawName);
        String extension = extensionOf(filename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("unsupported file type ." + extension + "; allowed: " + ALLOWED_EXTENSIONS);
        }
        String text = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        List<Map<String, String>> docs;
        if ("json".equals(extension)) {
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

    private String sanitizeFilename(String rawName) {
        String baseName = Paths.get(rawName.replace('\\', '/')).getFileName().toString();
        String cleaned = baseName.replaceAll("[^\\w.\\-\\u4e00-\\u9fff]", "_");
        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
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
