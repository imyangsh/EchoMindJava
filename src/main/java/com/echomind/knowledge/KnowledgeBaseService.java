package com.echomind.knowledge;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final int EMBEDDING_BATCH_SIZE = 64;
    private static final double BM25_K1 = 1.5;
    private static final double BM25_B = 0.75;

    private final EchoMindProperties properties;
    private final EmbeddingStore<TextSegment> vectorStore;
    private final ObjectMapper objectMapper;
    private final TextEmbeddingService embeddingService;

    /**
     * 主存储：chunk id -> 文档 + 倒排索引条目（词频、文档长度）。
     * 入库时一次性分词并统计词频，检索时查表即可，避免旧实现每次查询
     * 对全部文档做 O(N²·T) 次重复分词。
     */
    private final Map<String, IndexedDocument> index = new ConcurrentHashMap<>();
    /** 全局词 -> 含该词的文档数（用于 IDF），与 index 同步维护。 */
    private final Map<String, Integer> docFrequency = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> postings = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingVectorWrites = new ConcurrentHashMap<>();
    private final AtomicLong vectorWriteGeneration = new AtomicLong();
    private final ReentrantLock vectorSyncLock = new ReentrantLock();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong corpusRevision = new AtomicLong();
    private final AtomicLong backendEpoch = new AtomicLong();
    private volatile String lastBackend = "unobserved";
    private final Object indexLock = new Object();
    private final Object persistLock = new Object();
    /** 持久化文件损坏时置位，阻止默认文档覆盖写坏用户数据。 */
    private volatile boolean loadFailed = false;

    public KnowledgeBaseService(EchoMindProperties properties, ObjectProvider<EmbeddingStore<TextSegment>> vectorStoreProvider, ObjectMapper objectMapper) {
        this(properties, vectorStoreProvider, objectMapper, TextEmbeddingService.hashOnly());
    }

    @Autowired
    public KnowledgeBaseService(EchoMindProperties properties, ObjectProvider<EmbeddingStore<TextSegment>> vectorStoreProvider, ObjectMapper objectMapper, TextEmbeddingService embeddingService) {
        this.properties = properties;
        this.vectorStore = embeddingService.externalEmbeddingAvailable() ? resolveVectorStore(vectorStoreProvider) : null;
        this.objectMapper = objectMapper;
        this.embeddingService = embeddingService;
    }

    /**
     * Chroma 延迟创建时仍可能因远端不可用抛异常；本地索引继续提供检索。
     */
    private static EmbeddingStore<TextSegment> resolveVectorStore(ObjectProvider<EmbeddingStore<TextSegment>> provider) {
        if (provider == null) return null;
        try {
            return provider.getIfAvailable();
        } catch (Exception ex) {
            log.warn("LangChain4j EmbeddingStore unavailable ({}); falling back to local BM25 + vector retrieval",
                    ex.getMessage());
            return null;
        }
    }

    @PostConstruct
    public void init() {
        loadPersistedDocuments();
        if (index.isEmpty() && !loadFailed) {
            addDocuments(defaultDocuments());
        }
    }

    public int addDocuments(List<Map<String, String>> inputDocs) {
        List<KnowledgeDocument> pending = new ArrayList<>();
        Set<String> replacingDocuments = new HashSet<>();
        for (Map<String, String> input : inputDocs) {
            String title = input.getOrDefault("title", "未命名文档");
            String content = input.getOrDefault("content", "");
            String source = input.getOrDefault("source", "echomind-java");
            String documentId = documentId(source, title);
            replacingDocuments.add(documentId);
            List<ChunkCandidate> chunks = splitIntoChunks(title, content);
            List<String> ids = new ArrayList<>();
            for (ChunkCandidate chunk : chunks) {
                // id 纳入 source 与完整 chunk 文本，避免同名不同源文档、
                // 或前 80 字相同的不同 chunk 撞 id 后互相覆盖。
                ids.add(md5(source + "|" + title + "|" + chunk.chunkIndex() + "|" + chunk.text()));
            }
            for (int i = 0; i < chunks.size(); i++) {
                ChunkCandidate chunk = chunks.get(i);
                String id = ids.get(i);
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("title", title);
                metadata.put("source", source);
                metadata.put("document_id", documentId);
                metadata.put("document_version", md5(content));
                metadata.put("chunk_index", chunk.chunkIndex());
                metadata.put("chunk_count", chunks.size());
                metadata.put("heading_path", chunk.headingPath());
                metadata.put("paragraph_start", chunk.paragraphStart());
                metadata.put("paragraph_end", chunk.paragraphEnd());
                metadata.put("previous_chunk_id", i > 0 ? ids.get(i - 1) : "");
                metadata.put("next_chunk_id", i + 1 < ids.size() ? ids.get(i + 1) : "");
                metadata.put("splitter", "heading-token-v2");
                metadata.put("token_count", tokenCount(chunk.text()));
                KnowledgeDocument doc = new KnowledgeDocument(
                        id,
                        title,
                        chunk.text(),
                        chunk.chunkIndex(),
                        metadata,
                        null
                );
                pending.add(doc);
            }
        }
        if (!pending.isEmpty()) {
            List<KnowledgeDocument> embedded = embedDocuments(pending);
            Set<String> newIds = embedded.stream().map(KnowledgeDocument::id).collect(java.util.stream.Collectors.toSet());
            List<String> removed = new ArrayList<>();
            synchronized (indexLock) {
                // Mark the remote index incomplete before any new local chunk becomes visible.
                if (vectorStore != null) {
                    long generation = vectorWriteGeneration.incrementAndGet();
                    newIds.forEach(id -> pendingVectorWrites.put(id, generation));
                }
                for (IndexedDocument existing : new ArrayList<>(index.values())) {
                    if (replacingDocuments.contains(documentIdOf(existing.doc())) && !newIds.contains(existing.doc().id())) {
                        removed.add(existing.doc().id());
                        removeDocument(existing.doc().id());
                    }
                }
                embedded.forEach(this::indexDocument);
                corpusRevision.incrementAndGet();
            }
            deleteFromVectorStore(removed);
            addToVectorStore(embedded);
            persistDocuments();
        }
        return pending.size();
    }

    private void indexDocument(KnowledgeDocument doc) {
        Map<String, Double> termFreq = new HashMap<>();
        addTerms(termFreq, doc.content(), 1.0);
        addTerms(termFreq, doc.title(), 2.0);
        String heading = metadataString(doc.metadata(), "heading_path");
        if (heading.startsWith(doc.title() + " > ")) heading = heading.substring(doc.title().length() + 3);
        if (!heading.equals(doc.title())) addTerms(termFreq, heading, 1.5);
        int length = Math.max(1, embeddingService.lexicalTokens(doc.content()).size());
        IndexedDocument entry = new IndexedDocument(doc, Map.copyOf(termFreq), length, embeddingService.hashEmbedding(searchableText(doc)));
        synchronized (indexLock) {
            removeDocument(doc.id(), false);
            index.put(doc.id(), entry);
            for (String term : termFreq.keySet()) {
                docFrequency.merge(term, 1, Integer::sum);
                postings.computeIfAbsent(term, ignored -> ConcurrentHashMap.newKeySet()).add(doc.id());
            }
            totalTokens.addAndGet(length);
        }
    }

    private void addTerms(Map<String, Double> frequencies, String text, double weight) {
        for (String term : embeddingService.lexicalTokens(text)) frequencies.merge(term, weight, Double::sum);
    }

    private void removeDocument(String id) {
        removeDocument(id, true);
    }

    private void removeDocument(String id, boolean clearPending) {
        IndexedDocument previous = index.remove(id);
        if (previous == null) return;
        if (clearPending) pendingVectorWrites.remove(id);
        for (String term : previous.termFreq().keySet()) {
            docFrequency.compute(term, (t, count) -> count == null || count <= 1 ? null : count - 1);
            postings.computeIfPresent(term, (t, ids) -> { ids.remove(id); return ids.isEmpty() ? null : ids; });
        }
        totalTokens.addAndGet(-previous.length());
    }

    public List<SearchResult> search(String query, int topK) {
        return searchDetailed(query, topK).results();
    }

    public RetrievalBatch searchDetailed(String query, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return new RetrievalBatch(List.of(), false, lastBackend);
        QueryContext initialContext = queryContext(query);
        if (vectorStore != null && initialContext.vector().external() && !pendingVectorWrites.isEmpty()) {
            // Rebuild any hash vectors left by an ingestion outage before retrying the remote write.
            ensureExternalSpace(initialContext.vector().space(), null);
            List<KnowledgeDocument> retry = pendingVectorWrites.keySet().stream().limit(EMBEDDING_BATCH_SIZE).map(index::get)
                    .filter(Objects::nonNull).map(IndexedDocument::doc).toList();
            if (synchronizeVectorStore(retry, false) && pendingVectorWrites.isEmpty()) persistDocuments();
        }
        ChromaScores chroma = chromaScores(initialContext.vector(), topK);
        boolean vectorStoreSynced;
        synchronized (indexLock) { vectorStoreSynced = pendingVectorWrites.isEmpty(); }
        boolean usingChroma = chroma.succeeded() && !chroma.ranking().isEmpty() && vectorStoreSynced;
        QueryContext context = !usingChroma && initialContext.vector().external() && !ensureExternalSpace(initialContext.vector().space(), null)
                ? hashContext(query, initialContext.terms()) : initialContext;
        Map<String, Double> semantic = usingChroma ? chroma.ranking() : vectorScores(context);
        boolean degraded = embeddingService.externalEmbeddingAvailable() && !context.vector().external()
                || vectorStore != null && (!chroma.succeeded() || !vectorStoreSynced);
        String backend = usingChroma ? "chroma:" + embeddingService.modelFingerprint() : "local:" + context.vector().space();
        observeBackend(backend + ":" + degraded);
        Map<String, Double> fused = fuseScores(bm25Scores(context.terms()), semantic);
        List<SearchResult> results = ranked(fused).stream().map(item -> {
            IndexedDocument entry = index.get(item.getKey());
            if (entry == null) return null;
            double localEvidence = relevanceScore(context, entry);
            double backendEvidence = usingChroma ? chroma.absolute().getOrDefault(item.getKey(), 0.0) : 0.0;
            Map<String, Object> metadata = evidenceMetadata(entry.doc(), context.vector().space(), degraded);
            metadata.put("evidence_query", query);
            if (backendEvidence > 0.0) metadata.put("backend_evidence_score", backendEvidence);
            if (backendEvidence > localEvidence) metadata.put("evidence_space", "chroma:" + embeddingService.modelFingerprint());
            return new SearchResult(entry.doc().id(), entry.doc().title(), entry.doc().content(),
                    round(Math.max(localEvidence, backendEvidence)), entry.doc().chunkIndex(), metadata, item.getValue(), 0);
        }).filter(Objects::nonNull).filter(result -> result.score() > 0.0).limit(topK).toList();
        return new RetrievalBatch(results, degraded, backend);
    }

    /** Preserve retrieval/rerank order while checking evidence against the user's original question. */
    public List<SearchResult> rescore(String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) return List.of();
        String evidenceQuery = query == null ? "" : query;
        QueryContext initialContext = queryContext(evidenceQuery);
        QueryContext context = initialContext.vector().external()
                && !ensureExternalSpace(initialContext.vector().space(), results.stream().map(SearchResult::id).toList())
                ? hashContext(evidenceQuery, initialContext.terms()) : initialContext;
        boolean degraded = embeddingService.externalEmbeddingAvailable() && !context.vector().external();
        return results.stream().map(result -> {
            IndexedDocument entry = index.get(result.id());
            if (entry == null) return result;
            Map<String, Object> metadata = evidenceMetadata(entry.doc(), context.vector().space(), degraded);
            metadata.putAll(result.metadata() == null ? Map.of() : result.metadata());
            metadata.put("evidence_space", context.vector().space());
            metadata.put("embedding_degraded", degraded);
            double localEvidence = relevanceScore(context, entry);
            double backendEvidence = evidenceQuery.equals(metadataString(result.metadata(), "evidence_query"))
                    && result.metadata() != null && result.metadata().get("backend_evidence_score") instanceof Number number
                    ? clamp(number.doubleValue()) : 0.0;
            if (backendEvidence > localEvidence) metadata.put("evidence_space", "chroma:" + embeddingService.modelFingerprint());
            metadata.put("evidence_query", evidenceQuery);
            if (backendEvidence > 0.0) metadata.put("backend_evidence_score", backendEvidence);
            else metadata.remove("backend_evidence_score");
            return new SearchResult(result.id(), result.title(), result.content(), round(Math.max(localEvidence, backendEvidence)),
                    result.chunk(), metadata, result.retrievalScore(), result.rerankRank());
        }).toList();
    }

    private QueryContext queryContext(String query) {
        TextEmbeddingService.EmbeddingVector vector = embeddingService.embedWithMetadata(query);
        return new QueryContext(Set.copyOf(embeddingService.lexicalTokens(query)), vector);
    }

    private QueryContext hashContext(String query, Set<String> terms) {
        return new QueryContext(terms, embeddingService.hashEmbedding(query));
    }

    private Map<String, Object> evidenceMetadata(KnowledgeDocument doc, String space, boolean degraded) {
        Map<String, Object> metadata = new LinkedHashMap<>(doc.metadata());
        metadata.put("evidence_space", space);
        metadata.put("embedding_degraded", degraded);
        return metadata;
    }

    public record RetrievalBatch(List<SearchResult> results, boolean degraded, String backendKey) { }
    private record QueryContext(Set<String> terms, TextEmbeddingService.EmbeddingVector vector) { }

    public long revision() { return corpusRevision.get(); }
    public String cacheFingerprint() {
        synchronized (indexLock) {
            return revision() + ":" + embeddingService.modelFingerprint() + ":" + embeddingService.recoveryEpoch() + ":" + backendEpoch.get();
        }
    }
    public int tokenCount(String text) { return embeddingService.tokenCount(text); }
    public String truncate(String text, int maxTokens) { return embeddingService.truncate(text, maxTokens); }

    private synchronized void observeBackend(String backend) {
        if (!backend.equals(lastBackend)) { lastBackend = backend; backendEpoch.incrementAndGet(); }
    }

    /**
     * 绝对相关性 = max(词级覆盖率, gram 覆盖率, 余弦相似度)，值域 [0,1]，
     * 与语料规模无关，可以与 min-relevant-score 阈值直接比较。
     * 词级覆盖率对英文/精确词命中敏感；2/3-gram 覆盖率对未分词的中文敏感，
     * 两者分开统计避免长查询片段稀释短语命中（如“我要退款”命中“退款”）。
     */
    private double relevanceScore(QueryContext context, IndexedDocument entry) {
        double wordCoverage = coverage(context.terms().stream().filter(term -> term.startsWith("w:")).toList(), entry);
        double gramCoverage = coverage(context.terms().stream().filter(term -> term.startsWith("g:")).toList(), entry);
        TextEmbeddingService.EmbeddingVector documentVector = context.vector().external() ? entry.doc().embedding() : entry.hashVector();
        double cosine = Math.max(0.0, embeddingService.cosine(context.vector(), documentVector));
        return Math.max(Math.max(wordCoverage, gramCoverage), cosine);
    }

    private double coverage(List<String> terms, IndexedDocument entry) {
        if (terms.isEmpty()) {
            return 0.0;
        }
        Set<String> distinct = new HashSet<>(terms);
        long hits = distinct.stream().filter(entry.termFreq()::containsKey).count();
        return (double) hits / distinct.size();
    }

    private Map<String, Double> fuseScores(Map<String, Double> bm25, Map<String, Double> vector) {
        String strategy = properties.getRag().getFusionStrategy();
        if ("rrf".equalsIgnoreCase(strategy)) {
            return reciprocalRankFusion(bm25, vector);
        }
        Map<String, Double> bm25Norm = normalize(bm25);
        Map<String, Double> vectorNorm = normalize(vector);
        Map<String, Double> fused = new HashMap<>();
        index.keySet().forEach(id -> {
            double vectorScore = vectorNorm.getOrDefault(id, 0.0);
            double score = properties.getRag().getBm25Weight() * bm25Norm.getOrDefault(id, 0.0)
                    + properties.getRag().getVectorWeight() * vectorScore;
            fused.put(id, score);
        });
        return fused;
    }

    private Map<String, Double> reciprocalRankFusion(Map<String, Double> bm25, Map<String, Double> vector) {
        double k = Math.max(1.0, properties.getRag().getRrfK());
        Map<String, Double> fused = new HashMap<>();
        addRrfScores(fused, bm25, k, properties.getRag().getBm25Weight());
        addRrfScores(fused, vector, k, properties.getRag().getVectorWeight());
        // RRF 融合分只用于排序，这里不再做归一化——归一化会把最高分抬成 1.0，
        // 一旦下游误用于阈值比较就会让相关性护栏失效。
        return fused;
    }

    private void addRrfScores(Map<String, Double> fused, Map<String, Double> scores, double k, double weight) {
        List<Map.Entry<String, Double>> ranked = ranked(scores);
        for (int i = 0; i < ranked.size(); i++) {
            fused.merge(ranked.get(i).getKey(), weight * (1.0 / (k + i + 1)), Double::sum);
        }
    }

    private List<Map.Entry<String, Double>> ranked(Map<String, Double> scores) {
        return scores.entrySet().stream().filter(item -> item.getValue() != null && item.getValue() > 0.0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).toList();
    }

    public List<SearchResult> expandWithNeighbors(List<SearchResult> results, int window) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        int budget = Math.max(1, properties.getRag().getContextMaxTokens());
        int used = 0;
        LinkedHashMap<String, SearchResult> expanded = new LinkedHashMap<>();
        // Reserve space for the ranked anchors before adding surrounding context.
        for (SearchResult result : results) {
            if (expanded.containsKey(result.id())) continue;
            int cost = tokenCount(result.content()) + 2;
            if (used + cost > budget) {
                String content = truncate(result.content(), Math.max(0, budget - used - 2));
                if (content.isBlank()) break;
                Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
                metadata.put("context_truncated", true);
                result = new SearchResult(result.id(), result.title(), content, result.score(), result.chunk(), metadata,
                        result.retrievalScore(), result.rerankRank());
                cost = tokenCount(content) + 2;
            }
            expanded.put(result.id(), result);
            used += cost;
        }
        if (window > 0) {
            for (SearchResult anchor : results) {
                if (!expanded.containsKey(anchor.id())) continue;
                List<SearchResult> neighbors = new ArrayList<>(neighborResults(anchor, "previous_chunk_id", window));
                neighbors.addAll(neighborResults(anchor, "next_chunk_id", window));
                for (SearchResult neighbor : neighbors) {
                    if (expanded.containsKey(neighbor.id())) continue;
                    int cost = tokenCount(neighbor.content()) + 2;
                    if (used + cost > budget) continue;
                    expanded.put(neighbor.id(), neighbor);
                    used += cost;
                }
            }
        }
        // Remove overlapping paragraphs within a document without dropping any anchor's provenance.
        Map<String, Set<String>> seen = new HashMap<>();
        List<SearchResult> context = new ArrayList<>();
        for (SearchResult result : expanded.values()) {
            String key = metadataString(result.metadata(), "document_id") + "|" + metadataString(result.metadata(), "heading_path");
            Set<String> paragraphs = seen.computeIfAbsent(key, ignored -> new HashSet<>());
            List<String> unique = java.util.Arrays.stream(result.content().split("\\n\\n")).filter(paragraphs::add).toList();
            String content = String.join("\n\n", unique);
            if (!content.isBlank()) context.add(new SearchResult(result.id(), result.title(), content, result.score(), result.chunk(),
                    result.metadata(), result.retrievalScore(), result.rerankRank()));
        }
        return context;
    }

    public int docCount() {
        return index.size();
    }

    private List<SearchResult> neighborResults(SearchResult anchor, String directionKey, int window) {
        List<SearchResult> neighbors = new ArrayList<>();
        SearchResult current = anchor;
        for (int i = 0; i < window; i++) {
            String id = metadataString(current.metadata(), directionKey);
            if (id.isBlank()) {
                break;
            }
            IndexedDocument entry = index.get(id);
            if (entry == null) {
                break;
            }
            if (!documentIdOf(entry.doc()).equals(metadataString(anchor.metadata(), "document_id"))
                    || !metadataString(entry.doc().metadata(), "heading_path").equals(metadataString(anchor.metadata(), "heading_path"))) break;
            SearchResult neighbor = toSearchResult(entry.doc(), 0.0, anchor.id());
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
        for (ParagraphBlock block : blocks) {
            int maximum = Math.max(16, properties.getRag().getMaxChunkTokens());
            int headerTokens = tokenCount(truncate(title + "\n" + block.headingPath(), maximum / 4)) + 2;
            int bodyBudget = Math.max(1, maximum - headerTokens);
            for (String piece : splitLongParagraph(block.text(), bodyBudget)) {
                ParagraphBlock next = new ParagraphBlock(block.paragraphIndex(), block.headingPath(), piece);
                boolean newSection = !current.isEmpty() && !current.getFirst().headingPath().equals(next.headingPath());
                if (!current.isEmpty() && (newSection || tokenCount(joinBlocks(current) + "\n\n" + piece) > bodyBudget)) {
                    chunks.add(toChunk(chunks.size(), current));
                    current = newSection ? new ArrayList<>() : overlapTail(current, Math.min(properties.getRag().getOverlapTokens(), bodyBudget / 4));
                    while (!current.isEmpty() && tokenCount(joinBlocks(current) + "\n\n" + piece) > bodyBudget) current.removeFirst();
                }
                current.add(next);
            }
        }
        if (!current.isEmpty()) {
            chunks.add(toChunk(chunks.size(), current));
        }
        return chunks;
    }

    private List<String> splitLongParagraph(String text, int budget) {
        List<String> pieces = new ArrayList<>();
        String remaining = text;
        while (!remaining.isBlank()) {
            String prefix = truncate(remaining, budget);
            if (prefix.isEmpty()) { // A single Unicode code point can require several tokenizer tokens.
                prefix = new String(Character.toChars(remaining.codePointAt(0)));
            }
            if (prefix.length() < remaining.length()) {
                int boundary = -1;
                for (int i = 0; i < prefix.length(); i++) {
                    if ("。！？.!?\n".indexOf(prefix.charAt(i)) >= 0) boundary = i + 1;
                }
                if (boundary >= prefix.length() / 2) prefix = prefix.substring(0, boundary);
            }
            if (!prefix.isBlank()) pieces.add(prefix.trim());
            remaining = remaining.substring(prefix.length()).stripLeading();
        }
        return pieces;
    }

    private String joinBlocks(List<ParagraphBlock> blocks) {
        return String.join("\n\n", blocks.stream().map(ParagraphBlock::text).toList());
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

    private List<ParagraphBlock> overlapTail(List<ParagraphBlock> blocks, int budget) {
        if (budget <= 0 || blocks.isEmpty()) {
            return new ArrayList<>();
        }
        ParagraphBlock last = blocks.getLast();
        String text = last.text();
        int[] points = text.codePoints().toArray();
        int low = 0, high = points.length;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (tokenCount(new String(points, points.length - middle, middle)) <= budget) low = middle;
            else high = middle - 1;
        }
        String tail = new String(points, points.length - low, low).trim();
        if (tail.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(List.of(new ParagraphBlock(last.paragraphIndex(), last.headingPath(), tail)));
    }

    private ChunkCandidate toChunk(int chunkIndex, List<ParagraphBlock> blocks) {
        String headingPath = blocks.getFirst().headingPath();
        String text = joinBlocks(blocks);
        int paragraphStart = blocks.stream().mapToInt(ParagraphBlock::paragraphIndex).min().orElse(0);
        int paragraphEnd = blocks.stream().mapToInt(ParagraphBlock::paragraphIndex).max().orElse(paragraphStart);
        return new ChunkCandidate(chunkIndex, headingPath, text, paragraphStart, paragraphEnd);
    }

    private String searchableText(KnowledgeDocument doc) {
        Object headingPath = doc.metadata() == null ? "" : doc.metadata().getOrDefault("heading_path", "");
        String header = truncate(doc.title() + "\n" + headingPath, Math.max(4, properties.getRag().getMaxChunkTokens() / 4));
        return header + "\n" + doc.content();
    }

    private void addToVectorStore(List<KnowledgeDocument> documents) {
        synchronizeVectorStore(documents, true);
    }

    /** Searches skip an in-flight writer; only its matching generation can clear pending state. */
    private boolean synchronizeVectorStore(List<KnowledgeDocument> documents, boolean waitForWriter) {
        if (vectorStore == null || documents.isEmpty()) return false;
        if (waitForWriter) vectorSyncLock.lock();
        else if (!vectorSyncLock.tryLock()) return false;
        try {
            for (int offset = 0; offset < documents.size(); offset += EMBEDDING_BATCH_SIZE) {
                List<VectorWrite> batch = new ArrayList<>();
                synchronized (indexLock) {
                    for (KnowledgeDocument doc : documents.subList(offset, Math.min(documents.size(), offset + EMBEDDING_BATCH_SIZE))) {
                        Long generation = pendingVectorWrites.get(doc.id());
                        IndexedDocument current = index.get(doc.id());
                        if (generation != null && current != null && Objects.equals(doc.metadata().get("document_version"), current.doc().metadata().get("document_version"))) {
                            batch.add(new VectorWrite(doc, generation));
                        }
                    }
                }
                if (batch.isEmpty()) continue;
                try {
                    String space = batch.getFirst().doc().embedding() == null ? "" : batch.getFirst().doc().embedding().space();
                    if (batch.stream().anyMatch(write -> write.doc().embedding() == null
                            || !write.doc().embedding().external() || !space.equals(write.doc().embedding().space()))) continue;
                    vectorStore.addAll(batch.stream().map(write -> write.doc().id()).toList(),
                            batch.stream().map(write -> toEmbedding(write.doc().embedding())).toList(),
                            batch.stream().map(write -> TextSegment.from(searchableText(write.doc()), new Metadata(write.doc().metadata()))).toList());
                    synchronized (indexLock) {
                        boolean changed = false;
                        for (VectorWrite write : batch) changed |= pendingVectorWrites.remove(write.doc().id(), write.generation());
                        if (changed) backendEpoch.incrementAndGet();
                    }
                } catch (Exception ex) {
                    observeBackend("chroma-write-failed");
                    log.debug("LangChain4j EmbeddingStore batch write skipped: {}", ex.getMessage());
                }
            }
            return true;
        } finally {
            vectorSyncLock.unlock();
        }
    }

    private record VectorWrite(KnowledgeDocument doc, long generation) { }

    private void deleteFromVectorStore(List<String> ids) {
        if (vectorStore == null || ids.isEmpty()) return;
        try { vectorStore.removeAll(ids); }
        catch (Exception ex) { log.debug("LangChain4j EmbeddingStore stale chunk cleanup skipped: {}", ex.getMessage()); }
    }

    private List<KnowledgeDocument> embedDocuments(List<KnowledgeDocument> documents) {
        List<KnowledgeDocument> embedded = new ArrayList<>(documents.size());
        boolean unavailable = false;
        for (int offset = 0; offset < documents.size(); offset += EMBEDDING_BATCH_SIZE) {
            List<KnowledgeDocument> batch = documents.subList(offset, Math.min(documents.size(), offset + EMBEDDING_BATCH_SIZE));
            List<String> texts = batch.stream().map(this::searchableText).toList();
            List<TextEmbeddingService.EmbeddingVector> vectors = unavailable
                    ? texts.stream().map(embeddingService::hashEmbedding).toList() : embeddingService.embedBatch(texts);
            if (embeddingService.externalEmbeddingAvailable() && vectors.stream().anyMatch(vector -> vector == null || !vector.external())) unavailable = true;
            for (int i = 0; i < batch.size(); i++) {
                KnowledgeDocument doc = batch.get(i);
                embedded.add(new KnowledgeDocument(doc.id(), doc.title(), doc.content(), doc.chunkIndex(), doc.metadata(), vectors.get(i)));
            }
        }
        return embedded;
    }

    /** Rebuild missing vectors without ever publishing a partially recovered index. */
    private boolean ensureExternalSpace(String space, List<String> candidateIds) {
        List<KnowledgeDocument> missing;
        long revision;
        synchronized (indexLock) {
            revision = revision();
            java.util.stream.Stream<IndexedDocument> entries = candidateIds == null ? index.values().stream()
                    : candidateIds.stream().distinct().map(index::get).filter(Objects::nonNull);
            missing = entries.map(IndexedDocument::doc)
                    .filter(doc -> doc.embedding() == null || !space.equals(doc.embedding().space())).toList();
        }
        if (missing.isEmpty()) return true;
        missing.forEach(doc -> embeddingService.forgetOtherSpace(searchableText(doc), space));
        List<KnowledgeDocument> rebuilt = embedDocuments(missing);
        if (rebuilt.stream().anyMatch(doc -> doc.embedding() == null || !space.equals(doc.embedding().space()))) return false;
        synchronized (indexLock) {
            if (revision != revision()) return false;
            for (KnowledgeDocument doc : rebuilt) {
                IndexedDocument previous = index.get(doc.id());
                if (previous == null) return false;
                index.put(doc.id(), new IndexedDocument(doc, previous.termFreq(), previous.length(), previous.hashVector()));
            }
            backendEpoch.incrementAndGet();
        }
        persistDocuments();
        return true;
    }

    private void loadPersistedDocuments() {
        Path path = Path.of(properties.getStorage().getKnowledgePath());
        if (!Files.exists(path)) {
            return;
        }
        try {
            List<StoredKnowledgeDocument> stored = objectMapper.readValue(path.toFile(), new TypeReference<>() {
            });
            List<KnowledgeDocument> missing = new ArrayList<>();
            for (StoredKnowledgeDocument item : stored) {
                if (item.content() == null || item.content().isBlank()) {
                    continue;
                }
                String title = item.title() == null || item.title().isBlank() ? "未命名文档" : item.title();
                Map<String, Object> metadata = new LinkedHashMap<>(item.metadata() == null ? Map.of() : item.metadata());
                metadata.putIfAbsent("document_id", documentId(metadataString(metadata, "source"), title));
                metadata.putIfAbsent("document_version", "legacy");
                KnowledgeDocument doc = new KnowledgeDocument(
                        item.id() == null || item.id().isBlank() ? md5(title + item.chunkIndex() + item.content()) : item.id(),
                        title,
                        item.content(),
                        item.chunkIndex(),
                        metadata,
                        item.embedding()
                );
                boolean reusable = embeddingService.modelFingerprint().equals(item.modelFingerprint())
                        && md5(searchableText(doc)).equals(item.contentFingerprint()) && embeddingService.compatible(item.embedding());
                if (!reusable) {
                    doc = new KnowledgeDocument(doc.id(), doc.title(), doc.content(), doc.chunkIndex(), doc.metadata(), null);
                    missing.add(doc);
                } else embeddingService.remember(searchableText(doc), doc.embedding());
                indexDocument(doc);
                if (vectorStore != null && (!reusable || !item.vectorSynced())) pendingVectorWrites.put(doc.id(), vectorWriteGeneration.incrementAndGet());
            }
            if (!missing.isEmpty()) {
                List<KnowledgeDocument> rebuilt = embedDocuments(missing);
                rebuilt.forEach(this::indexDocument);
                addToVectorStore(rebuilt);
                persistDocuments();
            }
            if (!index.isEmpty()) corpusRevision.incrementAndGet();
            log.info("Loaded {} persisted knowledge chunks from {}", index.size(), path);
        } catch (Exception ex) {
            // 加载失败时绝不能让后续的“空库 -> 注入默认文档 -> 覆盖写”把用户数据抹掉。
            // 把坏文件改名保留现场，并置 loadFailed 阻止默认文档写入。
            loadFailed = true;
            log.error("Failed to load persisted knowledge store {}; renaming corrupt file and skipping default seeding", path, ex);
            try {
                Path corrupt = path.resolveSibling(path.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());
                Files.move(path, corrupt, StandardCopyOption.REPLACE_EXISTING);
                log.error("Corrupt knowledge store preserved at {}", corrupt);
            } catch (Exception moveEx) {
                log.error("Failed to preserve corrupt knowledge store: {}", moveEx.getMessage());
            }
        }
    }

    private void persistDocuments() {
        Path path = Path.of(properties.getStorage().getKnowledgePath());
        synchronized (persistLock) {
            try {
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                List<StoredKnowledgeDocument> stored;
                synchronized (indexLock) {
                    stored = index.values().stream()
                            .map(IndexedDocument::doc)
                            .sorted(Comparator.comparing(KnowledgeDocument::id))
                            .map(doc -> new StoredKnowledgeDocument(doc.id(), doc.title(), doc.content(), doc.chunkIndex(), doc.metadata(),
                                    doc.embedding(), embeddingService.modelFingerprint(), md5(searchableText(doc)),
                                    vectorStore != null && !pendingVectorWrites.containsKey(doc.id())))
                            .toList();
                }
                // 写临时文件后原子替换，避免写一半崩溃留下残缺 JSON。
                Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), stored);
                try {
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception ex) {
                log.warn("Failed to persist knowledge store {}: {}", path, ex.getMessage());
            }
        }
    }

    private Map<String, Double> bm25Scores(Set<String> queryTerms) {
        Map<String, Double> scores = new HashMap<>();
        if (index.isEmpty()) {
            return scores;
        }
        if (queryTerms.isEmpty()) {
            return scores;
        }
        int docCount = index.size();
        double avgDl = Math.max(1.0, (double) totalTokens.get() / docCount);
        Set<String> candidateIds = new HashSet<>();
        for (String term : queryTerms) candidateIds.addAll(postings.getOrDefault(term, Set.of()));
        for (String id : candidateIds) {
            IndexedDocument entry = index.get(id);
            if (entry == null) continue;
            double score = 0.0;
            for (String term : queryTerms) {
                double tf = entry.termFreq().getOrDefault(term, 0.0);
                if (tf == 0) {
                    continue;
                }
                int df = docFrequency.getOrDefault(term, 0);
                double idf = Math.log(1 + (docCount - df + 0.5) / (df + 0.5));
                double denom = tf + BM25_K1 * (1 - BM25_B + BM25_B * entry.length() / avgDl);
                score += idf * (tf * (BM25_K1 + 1)) / denom;
            }
            if (score > 0.0) {
                scores.put(entry.doc().id(), score);
            }
        }
        return scores;
    }

    private Map<String, Double> vectorScores(QueryContext context) {
        Map<String, Double> scores = new HashMap<>();
        for (IndexedDocument entry : index.values()) {
            TextEmbeddingService.EmbeddingVector vector = context.vector().external() ? entry.doc().embedding() : entry.hashVector();
            double cosine = Math.max(0.0, embeddingService.cosine(context.vector(), vector));
            if (cosine > 0.0) {
                scores.put(entry.doc().id(), cosine);
            }
        }
        return scores;
    }

    private ChromaScores chromaScores(TextEmbeddingService.EmbeddingVector queryVector, int topK) {
        if (vectorStore == null || queryVector == null || !queryVector.external()) {
            return new ChromaScores(Map.of(), Map.of(), vectorStore == null);
        }
        try {
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(toEmbedding(queryVector))
                    .maxResults(Math.max(1, topK))
                    .minScore(0.0)
                    .build();
            List<EmbeddingMatch<TextSegment>> results = vectorStore.search(request).matches();
            Map<String, Double> scores = new HashMap<>();
            Map<String, Double> absolute = new HashMap<>();
            boolean complete = true;
            for (int i = 0; i < results.size(); i++) {
                EmbeddingMatch<TextSegment> match = results.get(i);
                String id = match.embeddingId();
                if (id == null || id.isBlank() || !index.containsKey(id)) {
                    // Stale remote chunks consume topK slots after a failed replacement cleanup.
                    complete = false;
                    continue;
                }
                // LangChain4j maps cosine [-1,1] to [0,1]. Use the returned vector's actual
                // cosine for evidence so the existing RAG threshold keeps its meaning, also for L2 collections.
                double score = match.embedding() == null ? clamp(2 * match.score() - 1)
                        : clamp(embeddingService.cosine(queryVector.values(), toDoubleArray(match.embedding().vector())));
                scores.put(id, Math.max(scores.getOrDefault(id, 0.0), score));
                absolute.put(id, score);
            }
            return new ChromaScores(scores, absolute, complete);
        } catch (Exception ex) {
            log.debug("LangChain4j EmbeddingStore search skipped: {}", ex.getMessage());
            return new ChromaScores(Map.of(), Map.of(), false);
        }
    }

    private static Embedding toEmbedding(TextEmbeddingService.EmbeddingVector vector) {
        float[] values = new float[vector.values().length];
        for (int i = 0; i < values.length; i++) values[i] = (float) vector.values()[i];
        return Embedding.from(values);
    }

    private static double[] toDoubleArray(float[] vector) {
        double[] values = new double[vector.length];
        for (int i = 0; i < values.length; i++) values[i] = vector[i];
        return values;
    }

    private double clamp(double value) { return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0; }
    private record ChromaScores(Map<String, Double> ranking, Map<String, Double> absolute, boolean succeeded) { }

    public static String documentId(String source, String title) { return md5((source == null ? "" : source) + "|" + title); }
    private String documentIdOf(KnowledgeDocument doc) {
        String id = metadataString(doc.metadata(), "document_id");
        return id.isBlank() ? documentId(metadataString(doc.metadata(), "source"), doc.title()) : id;
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

    private static String md5(String value) {
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

    private record IndexedDocument(KnowledgeDocument doc, Map<String, Double> termFreq, int length, TextEmbeddingService.EmbeddingVector hashVector) {
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
            Map<String, Object> metadata,
            TextEmbeddingService.EmbeddingVector embedding,
            String modelFingerprint,
            String contentFingerprint,
            boolean vectorSynced
    ) {
    }
}
