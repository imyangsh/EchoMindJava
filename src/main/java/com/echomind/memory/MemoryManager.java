package com.echomind.memory;

import com.echomind.config.EchoMindProperties;
import com.echomind.knowledge.TextEmbeddingService;
import com.echomind.llm.LlmGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    /** 情景记忆条数上限，防止长期运行内存与持久化文件无界增长。 */
    private static final int MAX_EPISODIC_ENTRIES = 500;
    /** 情景记忆召回的最低余弦相似度，低于该值的旧摘要不注入 prompt。 */
    private static final double EPISODIC_MIN_SIMILARITY = 0.35;
    private static final int BACKGROUND_WORKERS = 2;
    private static final int BACKGROUND_QUEUE_CAPACITY = 128;
    /** Compare the whole captured suffix, retaining every message prepended during summarization. */
    private static final DefaultRedisScript<Long> COMPRESS_SCRIPT = new DefaultRedisScript<>("""
            local expected = tonumber(ARGV[1])
            local keep = tonumber(ARGV[2])
            local size = redis.call('LLEN', KEYS[1])
            if size < expected then return 0 end
            local appended = size - expected
            for i = 0, expected - 1 do
                if redis.call('LINDEX', KEYS[1], appended + i) ~= ARGV[5 + i] then return 0 end
            end
            local previous = redis.call('GET', KEYS[2])
            local summary = ARGV[4]
            if previous and previous ~= '' then summary = previous .. '\\n' .. summary end
            redis.call('SET', KEYS[2], summary, 'EX', ARGV[3])
            redis.call('LTRIM', KEYS[1], 0, appended + keep - 1)
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final EchoMindProperties properties;
    private final LlmGateway llmGateway;
    private final TextEmbeddingService embeddingService;
    private final List<EpisodicEntry> episodicStore = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, Object>> profileStore = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor backgroundExecutor;
    /** One coalesced task per session; entries exist only while queued/running and are bounded. */
    private final Map<String, BackgroundWork> pendingWork = new HashMap<>();
    private final Object workLock = new Object();

    public MemoryManager(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, EchoMindProperties properties, LlmGateway llmGateway, TextEmbeddingService embeddingService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.llmGateway = llmGateway;
        this.embeddingService = embeddingService;
        AtomicInteger threadIds = new AtomicInteger();
        this.backgroundExecutor = new ThreadPoolExecutor(BACKGROUND_WORKERS, BACKGROUND_WORKERS,
                0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(BACKGROUND_QUEUE_CAPACITY), task -> {
                    Thread thread = new Thread(task, "memory-background-" + threadIds.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PostConstruct
    public void init() {
        loadPersistentMemory();
    }

    @PreDestroy
    public void destroy() {
        backgroundExecutor.shutdownNow();
        synchronized (workLock) {
            pendingWork.clear();
        }
        try {
            if (!backgroundExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Memory background workers did not terminate within the shutdown grace period");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public MemoryContext getContext(String userId, String conversationId, String query) {
        List<ConversationMessage> recent = getWorkingMemory(userId, conversationId);
        List<String> relevantHistory = searchEpisodic(userId, query);
        Map<String, Object> profile = profileStore.getOrDefault(userId, Map.of());
        String summary = safeRedisGet(summaryKey(userId, conversationId));
        return new MemoryContext(recent, relevantHistory, profile, summary == null ? "" : summary);
    }

    public void addMessage(String userId, String conversationId, MessageRole role, String content) {
        addMessage(userId, conversationId, role, content, Map.of());
    }

    public void addMessage(String userId, String conversationId, MessageRole role, String content, Map<String, Object> metadata) {
        appendMessages(userId, conversationId, List.of(message(role, content, metadata)));
    }

    /** A single Redis LPUSH stores the complete turn in chronological order without interleaving. */
    public void addTurn(String userId, String conversationId, String userContent, String assistantContent,
                        Map<String, Object> assistantMetadata) {
        appendMessages(userId, conversationId, List.of(message(MessageRole.USER, userContent, Map.of()),
                message(MessageRole.ASSISTANT, assistantContent, assistantMetadata)));
    }

    private ConversationMessage message(MessageRole role, String content, Map<String, Object> metadata) {
        return new ConversationMessage(role, safe(content), Instant.now(),
                metadata == null ? Map.of() : Map.copyOf(metadata));
    }

    private void appendMessages(String userId, String conversationId, List<ConversationMessage> messages) {
        String key = wmKey(userId, conversationId);
        try {
            List<String> serialized = new ArrayList<>(messages.size());
            for (ConversationMessage message : messages) serialized.add(objectMapper.writeValueAsString(message));
            Long size = redisTemplate.opsForList().leftPushAll(key, serialized);
            redisTemplate.expire(key, Duration.ofSeconds(properties.getMemory().getTtlSeconds()));
            if (size != null && size >= properties.getMemory().getCompressAt()) {
                scheduleWork(userId, conversationId, true, false, false);
            }
        } catch (Exception ex) {
            log.warn("Failed to write working memory: {}", ex.getMessage());
        }
    }

    public void updateProfile(String userId, String conversationId) {
        scheduleWork(userId, conversationId, false, true, false);
    }

    private void refreshProfile(String userId, String conversationId) {
        List<ConversationMessage> messages = getWorkingMemory(userId, conversationId).stream()
                .filter(this::trustedForLongTerm).toList();
        if (messages.isEmpty()) {
            return;
        }
        String text = tail(messages, 10).stream()
                .map(m -> m.role().name().toLowerCase() + ": " + m.content())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        String prompt = """
                从以下客服对话中提炼用户画像，只记录用户明确表达的偏好或实体，不要把助手建议当成用户事实，返回 JSON：
                %s
                格式: {"preferences":["..."],"entities":{"产品":[],"问题类型":[]}}
                """.formatted(text);
        try {
            var reply = llmGateway.chatResult("", prompt, 0.0, 512);
            if (reply.degraded()) return;
            String raw = reply.content();
            Map<String, Object> profile = objectMapper.readValue(sliceJson(raw), new TypeReference<>() {
            });
            // 字段级合并而非整体覆盖：同一用户多个会话并发更新画像时，
            // 整体 put 会让后写会话把先写会话的偏好全部抹掉。
            profileStore.merge(userId, profile, this::mergeProfiles);
            persistMemory();
        } catch (Exception ex) {
            log.warn("Profile update failed: {}", ex.getMessage());
        }
    }

    public List<ConversationMessage> getWorkingMemory(String userId, String conversationId) {
        try {
            String key = wmKey(userId, conversationId);
            List<String> raw = redisTemplate.opsForList().range(key, 0, properties.getMemory().getWorkingMax() - 1);
            if (raw == null) {
                return List.of();
            }
            List<ConversationMessage> messages = new ArrayList<>();
            for (int i = raw.size() - 1; i >= 0; i--) {
                messages.add(objectMapper.readValue(raw.get(i), ConversationMessage.class));
            }
            return messages;
        } catch (Exception ex) {
            log.warn("Failed to read working memory: {}", ex.getMessage());
            return List.of();
        }
    }

    private void compress(String userId, String conversationId) {
        try {
            // Read the entire suffix: truncating this snapshot to workingMax could delete unseen history.
            List<String> snapshot = redisTemplate.opsForList().range(wmKey(userId, conversationId), 0, -1);
            if (snapshot == null || snapshot.size() < properties.getMemory().getCompressAt()) return;
            int keep = Math.min(5, snapshot.size());
            List<ConversationMessage> oldMessages = new ArrayList<>();
            for (int i = snapshot.size() - 1; i >= keep; i--) {
                oldMessages.add(objectMapper.readValue(snapshot.get(i), ConversationMessage.class));
            }
            String text = oldMessages.stream().filter(this::trustedForLongTerm)
                    .map(m -> m.role().name().toLowerCase() + ": " + m.content())
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            if (text.isBlank()) return;
            // Background work deliberately has no request ExecutionContext or request deadline.
            var reply = llmGateway.chatResult("", "用 2-3 句话总结以下对话关键信息：\n" + text, 0.0, 256);
            if (reply.degraded()) {
                log.warn("Skipping memory summary because the model returned a local fallback");
                return;
            }
            String summary = safe(reply.content()).trim();
            if (summary.isBlank() || Thread.currentThread().isInterrupted()) return;
            List<String> args = new ArrayList<>();
            args.add(Integer.toString(snapshot.size()));
            args.add(Integer.toString(keep));
            args.add(Long.toString(properties.getMemory().getTtlSeconds()));
            args.add(summary);
            args.addAll(snapshot);
            Long committed = redisTemplate.execute(COMPRESS_SCRIPT,
                    List.of(wmKey(userId, conversationId), summaryKey(userId, conversationId)), args.toArray());
            if (!Long.valueOf(1).equals(committed)) return;
            var embedding = embeddingService.embedWithMetadata(summary);
            synchronized (episodicStore) {
                episodicStore.add(new EpisodicEntry(userId, conversationId, summary, text, Instant.now(), embedding));
                while (episodicStore.size() > MAX_EPISODIC_ENTRIES) episodicStore.remove(0);
            }
            persistMemory();
        } catch (Exception ex) {
            log.warn("Memory compression failed: {}", ex.getMessage());
        }
    }

    private void scheduleWork(String userId, String conversationId, boolean compress, boolean profile, boolean persist) {
        String key = wmKey(userId, conversationId);
        synchronized (workLock) {
            if (backgroundExecutor.isShutdown()) return;
            BackgroundWork work = pendingWork.get(key);
            if (work != null) {
                work.compress |= compress;
                work.profile |= profile;
                work.persist |= persist;
                return;
            }
            if (pendingWork.size() >= BACKGROUND_WORKERS + BACKGROUND_QUEUE_CAPACITY) {
                log.warn("Memory background capacity reached; deferring maintenance until next activity");
                return;
            }
            work = new BackgroundWork(userId, conversationId, compress, profile, persist);
            pendingWork.put(key, work);
            BackgroundWork submitted = work;
            try {
                backgroundExecutor.execute(() -> runWork(key, submitted));
            } catch (RejectedExecutionException ex) {
                pendingWork.remove(key, work);
                log.warn("Memory background queue full; deferring maintenance until next activity");
            }
        }
    }

    private void runWork(String key, BackgroundWork work) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                boolean compress;
                boolean profile;
                boolean persist;
                synchronized (workLock) {
                    compress = work.compress;
                    profile = work.profile;
                    persist = work.persist;
                    work.compress = work.profile = work.persist = false;
                    if (!compress && !profile && !persist) {
                        pendingWork.remove(key, work);
                        return;
                    }
                }
                if (profile) refreshProfile(work.userId, work.conversationId);
                if (compress) compress(work.userId, work.conversationId);
                if (persist) persistMemory();
            }
        } finally {
            synchronized (workLock) {
                pendingWork.remove(key, work);
            }
        }
    }

    private static final class BackgroundWork {
        private final String userId;
        private final String conversationId;
        private boolean compress;
        private boolean profile;
        private boolean persist;

        private BackgroundWork(String userId, String conversationId, boolean compress, boolean profile, boolean persist) {
            this.userId = userId;
            this.conversationId = conversationId;
            this.compress = compress;
            this.profile = profile;
            this.persist = persist;
        }
    }

    /** Unknown or failed assistant drafts remain conversational history, never trusted profile/summary facts. */
    private boolean trustedForLongTerm(ConversationMessage message) {
        return message.role() == MessageRole.USER || (message.role() == MessageRole.ASSISTANT
                && message.metadata() != null && Boolean.TRUE.equals(message.metadata().get("trusted")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeProfiles(Map<String, Object> existing, Map<String, Object> update) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(existing);
        update.forEach((key, value) -> merged.merge(key, value, (oldValue, newValue) -> {
            if (oldValue instanceof List<?> oldList && newValue instanceof List<?> newList) {
                List<Object> union = new ArrayList<>(oldList);
                newList.stream().filter(item -> !union.contains(item)).forEach(union::add);
                return union;
            }
            if (oldValue instanceof Map<?, ?> oldMap && newValue instanceof Map<?, ?> newMap) {
                return mergeProfiles((Map<String, Object>) oldMap, (Map<String, Object>) newMap);
            }
            return newValue;
        }));
        return merged;
    }

    private List<String> searchEpisodic(String userId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<EpisodicEntry> candidates = episodicStore.stream().filter(e -> e.userId().equals(userId)).toList();
        if (candidates.isEmpty()) return List.of();
        var queryVec = embeddingService.embedWithMetadata(query);
        List<ScoredMemory> scored = new ArrayList<>();
        boolean recovered = false;
        for (EpisodicEntry entry : candidates) {
            var vector = entry.embedding();
            if (!usable(vector) || !queryVec.space().equals(vector.space())) {
                if (!queryVec.external()) {
                    // Lexical fallback is temporary; do not overwrite a previously valid external vector.
                    vector = embeddingService.hashEmbedding(entry.summary());
                } else {
                    embeddingService.forgetOtherSpace(entry.summary(), queryVec.space());
                    vector = embeddingService.embedWithMetadata(entry.summary());
                    if (usable(vector) && queryVec.space().equals(vector.space())) {
                        synchronized (episodicStore) {
                            int index = episodicStore.indexOf(entry);
                            if (index >= 0) {
                                episodicStore.set(index, new EpisodicEntry(entry.userId(), entry.conversationId(),
                                        entry.summary(), entry.fullText(), entry.timestamp(), vector));
                                recovered = true;
                            }
                        }
                    }
                }
            }
            // Typed cosine rejects different model spaces even when both have exactly 1024 dimensions.
            if (usable(vector)) {
                double similarity = embeddingService.cosine(queryVec, vector);
                if (similarity >= EPISODIC_MIN_SIMILARITY) scored.add(new ScoredMemory(entry.summary(), similarity));
            }
        }
        if (recovered) scheduleWork(userId, candidates.getFirst().conversationId(), false, false, true);
        return scored.stream().sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
                .limit(5).map(ScoredMemory::summary).toList();
    }

    private boolean usable(TextEmbeddingService.EmbeddingVector vector) {
        return vector != null && vector.space() != null && !vector.space().isBlank()
                && vector.values() != null && vector.values().length > 0
                && java.util.Arrays.stream(vector.values()).allMatch(Double::isFinite)
                && java.util.Arrays.stream(vector.values()).anyMatch(value -> value != 0.0);
    }

    private record ScoredMemory(String summary, double score) { }

    private String safeRedisGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<ConversationMessage> tail(List<ConversationMessage> messages, int n) {
        return messages.subList(Math.max(0, messages.size() - n), messages.size());
    }

    private String sliceJson(String raw) {
        String json = com.echomind.common.JsonSlices.sliceObject(raw);
        return json == null ? "{}" : json;
    }

    private String wmKey(String userId, String conversationId) {
        return "wm:" + keyPart(userId) + ":" + keyPart(conversationId);
    }

    private String summaryKey(String userId, String conversationId) {
        return "summary:" + keyPart(userId) + ":" + keyPart(conversationId);
    }

    /**
     * Redis key 分段转义：userId 或 conversationId 中出现 ':' 时，
     * ("a", "b:c") 与 ("a:b", "c") 会拼出同一个 key，造成跨用户记忆串号；
     * null/空值也不能退化成共享的空段。
     */
    private String keyPart(String value) {
        if (value == null || value.isBlank()) {
            return "anonymous";
        }
        return value.replace("%", "%25").replace(":", "%3A");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private void loadPersistentMemory() {
        Path path = Path.of(properties.getStorage().getMemoryPath());
        if (!Files.exists(path)) {
            return;
        }
        try {
            StoredMemory stored = objectMapper.readValue(path.toFile(), StoredMemory.class);
            if (stored.episodic() != null) {
                stored.episodic().forEach(entry -> {
                    if (entry.summary() != null && !entry.summary().isBlank()) {
                        episodicStore.add(new EpisodicEntry(
                                safe(entry.userId()),
                                safe(entry.conversationId()),
                                safe(entry.summary()),
                                safe(entry.fullText()),
                                entry.timestamp() == null ? Instant.now() : entry.timestamp(),
                                usable(entry.embedding()) ? entry.embedding() : null
                        ));
                        if (usable(entry.embedding())) embeddingService.remember(entry.summary(), entry.embedding());
                    }
                });
                while (episodicStore.size() > MAX_EPISODIC_ENTRIES) episodicStore.remove(0);
            }
            if (stored.profiles() != null) {
                profileStore.putAll(stored.profiles());
            }
            log.info("Loaded persisted memory: episodic={}, profiles={}", episodicStore.size(), profileStore.size());
        } catch (Exception ex) {
            log.warn("Failed to load persisted memory store {}: {}", path, ex.getMessage());
        }
    }

    private synchronized void persistMemory() {
        Path path = Path.of(properties.getStorage().getMemoryPath());
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            List<StoredEpisodicEntry> episodic = episodicStore.stream()
                    .map(entry -> new StoredEpisodicEntry(entry.userId(), entry.conversationId(), entry.summary(), entry.fullText(), entry.timestamp(), entry.embedding()))
                    .toList();
            // 临时文件 + 原子替换，避免写一半崩溃损坏持久化文件。
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(tmp.toFile(), new StoredMemory(episodic, Map.copyOf(profileStore)));
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            log.warn("Failed to persist memory store {}: {}", path, ex.getMessage());
        }
    }

    private record EpisodicEntry(String userId, String conversationId, String summary, String fullText, Instant timestamp,
                                 TextEmbeddingService.EmbeddingVector embedding) {
    }

    private record StoredMemory(List<StoredEpisodicEntry> episodic, Map<String, Map<String, Object>> profiles) {
        private StoredMemory {
            episodic = episodic == null ? List.of() : episodic;
            profiles = profiles == null ? Map.of() : profiles;
        }
    }

    private record StoredEpisodicEntry(String userId, String conversationId, String summary, String fullText, Instant timestamp,
                                      TextEmbeddingService.EmbeddingVector embedding) {
    }
}
