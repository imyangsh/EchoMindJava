package com.echomind.knowledge;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final int TARGET_CHUNK_CHARS = 700;
    private static final int OVERLAP_PARAGRAPHS = 1;

    private final EchoMindProperties properties;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final List<KnowledgeDocument> documents = new CopyOnWriteArrayList<>();

    public KnowledgeBaseService(EchoMindProperties properties, ObjectProvider<VectorStore> vectorStoreProvider, ObjectMapper objectMapper) {
        this.properties = properties;
        this.vectorStore = vectorStoreProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        loadPersistedDocuments();
        if (documents.isEmpty()) {
            addDocuments(defaultDocuments());
        }
    }

    public int addDocuments(List<Map<String, String>> inputDocs) {
        int added = 0;
        for (Map<String, String> input : inputDocs) {
            String title = input.getOrDefault("title", "未命名文档");
            String content = input.getOrDefault("content", "");
            String source = input.getOrDefault("source", "echomind-java");
            List<ChunkCandidate> chunks = splitIntoChunks(title, content);
            List<String> ids = new ArrayList<>();
            for (ChunkCandidate chunk : chunks) {
                ids.add(md5(title + "_" + chunk.chunkIndex() + "_" + chunk.text().substring(0, Math.min(80, chunk.text().length()))));
            }
            for (int i = 0; i < chunks.size(); i++) {
                ChunkCandidate chunk = chunks.get(i);
                String id = ids.get(i);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("title", title);
                metadata.put("source", source);
                metadata.put("chunk_index", chunk.chunkIndex());
                metadata.put("chunk_count", chunks.size());
                metadata.put("heading_path", chunk.headingPath());
                metadata.put("paragraph_start", chunk.paragraphStart());
                metadata.put("paragraph_end", chunk.paragraphEnd());
                metadata.put("previous_chunk_id", i > 0 ? ids.get(i - 1) : "");
                metadata.put("next_chunk_id", i + 1 < ids.size() ? ids.get(i + 1) : "");
                metadata.put("splitter", "heading-paragraph-v1");
                KnowledgeDocument doc = new KnowledgeDocument(
                        id,
                        title,
                        chunk.text(),
                        chunk.chunkIndex(),
                        metadata,
                        embed(embeddingText(title, chunk))
                );
                documents.removeIf(existing -> existing.id().equals(id));
                documents.add(doc);
                added++;
                addToSpringAiVectorStore(doc);
            }
        }
        if (added > 0) {
            persistDocuments();
        }
        return added;
    }

    public List<SearchResult> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Map<String, Double> bm25 = bm25Scores(query);
        Map<String, Double> vector = vectorScores(query);
        Map<String, Double> chroma = chromaScores(query, Math.max(topK, properties.getRag().getTopK()));
        Map<String, Double> fused = new HashMap<>();
        documents.forEach(doc -> {
            double vectorScore = Math.max(vector.getOrDefault(doc.id(), 0.0), chroma.getOrDefault(doc.id(), 0.0));
            double score = properties.getRag().getBm25Weight() * bm25.getOrDefault(doc.id(), 0.0)
                    + properties.getRag().getVectorWeight() * vectorScore;
            fused.put(doc.id(), score);
        });
        return documents.stream()
                .sorted(Comparator.comparingDouble((KnowledgeDocument d) -> fused.getOrDefault(d.id(), 0.0)).reversed())
                .limit(topK)
                .map(doc -> new SearchResult(
                        doc.id(),
                        doc.title(),
                        doc.content(),
                        round(fused.getOrDefault(doc.id(), 0.0)),
                        doc.chunkIndex(),
                        doc.metadata()
                ))
                .filter(result -> result.score() > 0.0)
                .toList();
    }

    public List<SearchResult> expandWithNeighbors(List<SearchResult> results, int window) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        if (window <= 0) {
            return List.copyOf(results);
        }
        Map<String, KnowledgeDocument> byId = documentsById();
        LinkedHashMap<String, SearchResult> expanded = new LinkedHashMap<>();
        for (SearchResult result : results) {
            List<SearchResult> previous = neighborResults(result, "previous_chunk_id", window, byId);
            for (int i = previous.size() - 1; i >= 0; i--) {
                addOrUpgrade(expanded, previous.get(i));
            }
            addOrUpgrade(expanded, result);
            neighborResults(result, "next_chunk_id", window, byId).forEach(item -> addOrUpgrade(expanded, item));
        }
        return new ArrayList<>(expanded.values());
    }

    public int docCount() {
        return documents.size();
    }

    private List<SearchResult> neighborResults(SearchResult anchor, String directionKey, int window, Map<String, KnowledgeDocument> byId) {
        List<SearchResult> neighbors = new ArrayList<>();
        SearchResult current = anchor;
        for (int i = 0; i < window; i++) {
            String id = metadataString(current.metadata(), directionKey);
            if (id.isBlank()) {
                break;
            }
            KnowledgeDocument doc = byId.get(id);
            if (doc == null) {
                break;
            }
            SearchResult neighbor = toSearchResult(doc, 0.0, anchor.id());
            neighbors.add(neighbor);
            current = neighbor;
        }
        return neighbors;
    }

    private void addOrUpgrade(LinkedHashMap<String, SearchResult> values, SearchResult result) {
        SearchResult existing = values.get(result.id());
        if (existing == null || (existing.score() <= 0.0 && result.score() > 0.0)) {
            values.put(result.id(), result);
        }
    }

    private Map<String, KnowledgeDocument> documentsById() {
        Map<String, KnowledgeDocument> byId = new HashMap<>();
        for (KnowledgeDocument doc : documents) {
            byId.put(doc.id(), doc);
        }
        return byId;
    }

    private SearchResult toSearchResult(KnowledgeDocument doc, double score, String anchorId) {
        Map<String, Object> metadata = new LinkedHashMap<>(doc.metadata() == null ? Map.of() : doc.metadata());
        metadata.put("context_expansion", true);
        metadata.put("anchor_chunk_id", anchorId == null ? "" : anchorId);
        return new SearchResult(doc.id(), doc.title(), doc.content(), score, doc.chunkIndex(), metadata);
    }

    private String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return "";
        }
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private List<ChunkCandidate> splitIntoChunks(String title, String content) {
        List<ParagraphBlock> blocks = parseParagraphs(title, content);
        if (blocks.isEmpty()) {
            return List.of();
        }
        List<ChunkCandidate> chunks = new ArrayList<>();
        List<ParagraphBlock> current = new ArrayList<>();
        int currentChars = 0;
        for (ParagraphBlock block : blocks) {
            int nextChars = currentChars + block.text().length() + 2;
            if (!current.isEmpty() && nextChars > TARGET_CHUNK_CHARS) {
                chunks.add(toChunk(chunks.size(), current));
                current = overlapTail(current);
                currentChars = current.stream().mapToInt(item -> item.text().length() + 2).sum();
            }
            current.add(block);
            currentChars += block.text().length() + 2;
        }
        if (!current.isEmpty()) {
            chunks.add(toChunk(chunks.size(), current));
        }
        return chunks;
    }

    private List<ParagraphBlock> parseParagraphs(String title, String content) {
        List<ParagraphBlock> blocks = new ArrayList<>();
        Map<Integer, String> headings = new LinkedHashMap<>();
        StringBuilder paragraph = new StringBuilder();
        int paragraphIndex = 0;
        for (String line : (content == null ? "" : content).replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String trimmed = line.trim();
            Heading heading = parseHeading(trimmed);
            if (heading != null) {
                paragraphIndex = flushParagraph(blocks, paragraph, paragraphIndex, headingPath(title, headings));
                headings.keySet().removeIf(level -> level >= heading.level());
                headings.put(heading.level(), heading.text());
                continue;
            }
            if (trimmed.isBlank()) {
                paragraphIndex = flushParagraph(blocks, paragraph, paragraphIndex, headingPath(title, headings));
                continue;
            }
            if (!paragraph.isEmpty()) {
                paragraph.append('\n');
            }
            paragraph.append(trimmed);
        }
        flushParagraph(blocks, paragraph, paragraphIndex, headingPath(title, headings));
        return blocks;
    }

    private int flushParagraph(List<ParagraphBlock> blocks, StringBuilder paragraph, int paragraphIndex, String headingPath) {
        String text = paragraph.toString().trim();
        paragraph.setLength(0);
        if (text.isBlank()) {
            return paragraphIndex;
        }
        blocks.add(new ParagraphBlock(paragraphIndex, headingPath, text));
        return paragraphIndex + 1;
    }

    private Heading parseHeading(String line) {
        if (!line.startsWith("#")) {
            return null;
        }
        int level = 0;
        while (level < line.length() && line.charAt(level) == '#') {
            level++;
        }
        if (level == 0 || level > 6 || level >= line.length() || !Character.isWhitespace(line.charAt(level))) {
            return null;
        }
        String text = line.substring(level).trim();
        return text.isBlank() ? null : new Heading(level, text);
    }

    private String headingPath(String title, Map<Integer, String> headings) {
        List<String> values = new ArrayList<>();
        if (title != null && !title.isBlank()) {
            values.add(title.trim());
        }
        headings.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .filter(value -> value != null && !value.isBlank())
                .forEach(values::add);
        return String.join(" > ", values);
    }

    private List<ParagraphBlock> overlapTail(List<ParagraphBlock> blocks) {
        if (OVERLAP_PARAGRAPHS <= 0 || blocks.isEmpty()) {
            return new ArrayList<>();
        }
        int start = Math.max(0, blocks.size() - OVERLAP_PARAGRAPHS);
        return new ArrayList<>(blocks.subList(start, blocks.size()));
    }

    private ChunkCandidate toChunk(int chunkIndex, List<ParagraphBlock> blocks) {
        String headingPath = blocks.getLast().headingPath();
        String text = String.join("\n\n", blocks.stream().map(ParagraphBlock::text).toList());
        int paragraphStart = blocks.stream().mapToInt(ParagraphBlock::paragraphIndex).min().orElse(0);
        int paragraphEnd = blocks.stream().mapToInt(ParagraphBlock::paragraphIndex).max().orElse(paragraphStart);
        return new ChunkCandidate(chunkIndex, headingPath, text, paragraphStart, paragraphEnd);
    }

    private String embeddingText(String title, ChunkCandidate chunk) {
        return String.join("\n", List.of(
                title == null ? "" : title,
                chunk.headingPath() == null ? "" : chunk.headingPath(),
                chunk.text()
        ));
    }

    private String searchableText(KnowledgeDocument doc) {
        Object headingPath = doc.metadata() == null ? "" : doc.metadata().getOrDefault("heading_path", "");
        return doc.title() + "\n" + headingPath + "\n" + doc.content();
    }

    private void addToSpringAiVectorStore(KnowledgeDocument doc) {
        if (vectorStore == null) {
            return;
        }
        try {
            org.springframework.ai.document.Document springDoc = new org.springframework.ai.document.Document(doc.id(), doc.content(), doc.metadata());
            vectorStore.add(List.of(springDoc));
        } catch (Exception ex) {
            log.debug("Spring AI VectorStore write skipped: {}", ex.getMessage());
        }
    }

    private void loadPersistedDocuments() {
        Path path = Path.of(properties.getStorage().getKnowledgePath());
        if (!Files.exists(path)) {
            return;
        }
        try {
            List<StoredKnowledgeDocument> stored = objectMapper.readValue(path.toFile(), new TypeReference<>() {
            });
            for (StoredKnowledgeDocument item : stored) {
                if (item.content() == null || item.content().isBlank()) {
                    continue;
                }
                String title = item.title() == null || item.title().isBlank() ? "未命名文档" : item.title();
                Map<String, Object> metadata = item.metadata() == null ? Map.of() : item.metadata();
                KnowledgeDocument doc = new KnowledgeDocument(
                        item.id() == null || item.id().isBlank() ? md5(title + item.chunkIndex() + item.content()) : item.id(),
                        title,
                        item.content(),
                        item.chunkIndex(),
                        metadata,
                        embed(title + "\n" + metadata.getOrDefault("heading_path", "") + "\n" + item.content())
                );
                documents.removeIf(existing -> existing.id().equals(doc.id()));
                documents.add(doc);
                addToSpringAiVectorStore(doc);
            }
            log.info("Loaded {} persisted knowledge chunks from {}", documents.size(), path);
        } catch (Exception ex) {
            log.warn("Failed to load persisted knowledge store {}: {}", path, ex.getMessage());
        }
    }

    private void persistDocuments() {
        Path path = Path.of(properties.getStorage().getKnowledgePath());
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            List<StoredKnowledgeDocument> stored = documents.stream()
                    .map(doc -> new StoredKnowledgeDocument(doc.id(), doc.title(), doc.content(), doc.chunkIndex(), doc.metadata()))
                    .toList();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), stored);
        } catch (Exception ex) {
            log.warn("Failed to persist knowledge store {}: {}", path, ex.getMessage());
        }
    }

    private Map<String, Double> bm25Scores(String query) {
        List<String> queryTerms = tokenize(query);
        Map<String, Double> scores = new HashMap<>();
        if (queryTerms.isEmpty() || documents.isEmpty()) {
            return scores;
        }
        double avgDl = documents.stream().mapToInt(d -> tokenize(searchableText(d)).size()).average().orElse(1.0);
        double k1 = 1.5;
        double b = 0.75;
        for (KnowledgeDocument doc : documents) {
            List<String> terms = tokenize(searchableText(doc));
            double score = 0.0;
            for (String term : queryTerms) {
                long tf = terms.stream().filter(term::equals).count();
                if (tf == 0) {
                    continue;
                }
                long df = documents.stream().filter(d -> tokenize(searchableText(d)).contains(term)).count();
                double idf = Math.log(1 + (documents.size() - df + 0.5) / (df + 0.5));
                double denom = tf + k1 * (1 - b + b * terms.size() / avgDl);
                score += idf * (tf * (k1 + 1)) / denom;
            }
            scores.put(doc.id(), score);
        }
        return normalize(scores);
    }

    private Map<String, Double> vectorScores(String query) {
        double[] queryVec = embed(query);
        Map<String, Double> scores = new HashMap<>();
        for (KnowledgeDocument doc : documents) {
            scores.put(doc.id(), Math.max(0.0, cosine(queryVec, doc.embedding())));
        }
        return normalize(scores);
    }

    private Map<String, Double> chromaScores(String query, int topK) {
        if (vectorStore == null || query == null || query.isBlank()) {
            return Map.of();
        }
        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(Math.max(1, topK))
                    .similarityThreshold(0.0)
                    .build();
            List<org.springframework.ai.document.Document> results = vectorStore.similaritySearch(request);
            Map<String, Double> scores = new HashMap<>();
            for (int i = 0; i < results.size(); i++) {
                org.springframework.ai.document.Document doc = results.get(i);
                String id = doc.getId();
                if (id == null || id.isBlank()) {
                    continue;
                }
                scores.put(id, Math.max(scores.getOrDefault(id, 0.0), 1.0 - (double) i / Math.max(1, results.size())));
            }
            return normalize(scores);
        } catch (Exception ex) {
            log.debug("Spring AI VectorStore search skipped: {}", ex.getMessage());
            return Map.of();
        }
    }

    private Map<String, Double> normalize(Map<String, Double> scores) {
        double max = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        if (max <= 0.0) {
            return scores;
        }
        Map<String, Double> normalized = new HashMap<>();
        scores.forEach((id, score) -> normalized.put(id, score / max));
        return normalized;
    }

    private List<String> tokenize(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", " ");
        List<String> tokens = new ArrayList<>();
        for (String part : normalized.split("\\s+")) {
            if (!part.isBlank()) {
                tokens.add(part);
            }
        }
        for (int n = 2; n <= 3; n++) {
            for (int i = 0; i + n <= normalized.length(); i++) {
                String gram = normalized.substring(i, i + n).trim();
                if (!gram.isBlank()) {
                    tokens.add(gram);
                }
            }
        }
        return tokens;
    }

    private double[] embed(String text) {
        double[] vector = new double[256];
        Set<String> grams = new HashSet<>(tokenize(text));
        for (String gram : grams) {
            int hash = gram.hashCode();
            int idx = Math.floorMod(hash, vector.length);
            vector[idx] += (hash & 1) == 0 ? 1.0 : -1.0;
        }
        return vector;
    }

    private double cosine(double[] a, double[] b) {
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0.0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private List<Map<String, String>> defaultDocuments() {
        List<Map<String, String>> docs = new ArrayList<>();
        docs.add(doc("退款政策", """
                用户在购买后 7 天内可以申请无理由退款。
                退款申请提交后，系统会在 1-3 个工作日内审核。
                审核通过后，款项将在 5-7 个工作日内退回原支付账户。
                商品已发货时，需要先完成退货流程。
                """));
        docs.add(doc("订单查询", """
                用户可以通过订单号查询订单状态。
                订单状态包括待支付、已支付、已发货、运输中、已签收、已完成。
                物流信息通常在发货后 24 小时内更新。
                """));
        docs.add(doc("账户安全", """
                建议用户定期修改密码，密码长度至少 8 位，包含字母和数字。
                如果忘记密码，可以通过绑定手机号或邮箱重置。
                发现异常登录时系统会锁定账户。
                """));
        docs.add(doc("技术故障排查", """
                应用崩溃请尝试清除缓存后重启应用。
                登录失败 401 表示认证失败。
                500 服务端错误通常是服务端问题，请稍后重试或联系技术支持。
                """));
        docs.add(doc("会员与积分", """
                每消费 1 元累计 1 积分，100 积分可抵扣 1 元。
                会员等级包括普通会员、银卡会员和金卡会员。
                积分有效期为 1 年。
                """));
        docs.add(doc("配送说明", """
                标准配送 3-5 个工作日送达，订单满 99 元免运费。
                加急配送 1-2 个工作日送达。
                同城配送可当日达或次日达。
                """));
        return docs;
    }

    private Map<String, String> doc(String title, String content) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("title", title);
        map.put("content", content);
        return map;
    }

    private record ParagraphBlock(int paragraphIndex, String headingPath, String text) {
    }

    private record ChunkCandidate(int chunkIndex, String headingPath, String text, int paragraphStart, int paragraphEnd) {
    }

    private record Heading(int level, String text) {
    }

    private record StoredKnowledgeDocument(
            String id,
            String title,
            String content,
            int chunkIndex,
            Map<String, Object> metadata
    ) {
    }
}
