package com.echomind.memory;

import com.echomind.config.EchoMindProperties;
import com.echomind.knowledge.TextEmbeddingService;
import com.echomind.llm.LlmGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);
    /** 情景记忆条数上限，防止长期运行内存与持久化文件无界增长。 */
    private static final int MAX_EPISODIC_ENTRIES = 500;
    /** 情景记忆召回的最低余弦相似度，低于该值的旧摘要不注入 prompt。 */
    private static final double EPISODIC_MIN_SIMILARITY = 0.35;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final EchoMindProperties properties;
    private final LlmGateway llmGateway;
    private final TextEmbeddingService embeddingService;
    private final List<EpisodicEntry> episodicStore = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, Object>> profileStore = new ConcurrentHashMap<>();

    public MemoryManager(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, EchoMindProperties properties, LlmGateway llmGateway, TextEmbeddingService embeddingService) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.llmGateway = llmGateway;
        this.embeddingService = embeddingService;
    }

    @PostConstruct
    public void init() {
        loadPersistentMemory();
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
        ConversationMessage message = new ConversationMessage(role, safe(content), Instant.now(),
                metadata == null ? Map.of() : Map.copyOf(metadata));
        String key = wmKey(userId, conversationId);
        try {
            redisTemplate.opsForList().leftPush(key, objectMapper.writeValueAsString(message));
            redisTemplate.expire(key, Duration.ofSeconds(properties.getMemory().getTtlSeconds()));
            Long size = redisTemplate.opsForList().size(key);
            if (size != null && size >= properties.getMemory().getCompressAt()) {
                compress(userId, conversationId);
            }
        } catch (Exception ex) {
            log.warn("Failed to write working memory: {}", ex.getMessage());
        }
    }

    @Async
    public void updateProfile(String userId, String conversationId) {
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
            String raw = llmGateway.chat("", prompt, 0.0, 512);
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
        com.echomind.agent.ExecutionContext execution = com.echomind.agent.ExecutionContext.current();
        List<ConversationMessage> messages = getWorkingMemory(userId, conversationId);
        if (messages.size() < properties.getMemory().getCompressAt()) {
            return;
        }
        int keep = Math.min(5, messages.size());
        List<ConversationMessage> oldMessages = messages.subList(0, messages.size() - keep);
        List<ConversationMessage> keepMessages = messages.subList(messages.size() - keep, messages.size());
        String text = oldMessages.stream()
                .filter(this::trustedForLongTerm)
                .map(m -> m.role().name().toLowerCase() + ": " + m.content())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
        String summary;
        if (text.isBlank()) {
            return;
        }
        try {
            if (execution != null) {
                execution.checkActive();
                execution.recordLlmCall();
            }
            var reply = llmGateway.chatResult("", "用 2-3 句话总结以下对话关键信息：\n" + text, 0.0, 256);
            if (execution != null) execution.recordLlmUsage(reply.inputTokens(), reply.outputTokens());
            if (reply.degraded()) {
                log.warn("Skipping memory summary because the model returned a local fallback");
                return;
            }
            summary = reply.content();
        } catch (Exception ex) {
            summary = "对话包含 " + oldMessages.size() + " 条历史消息。";
        }
        if (execution != null) execution.checkActive();
        episodicStore.add(new EpisodicEntry(userId, conversationId, summary, text, Instant.now(), embeddingService.embed(summary)));
        while (episodicStore.size() > MAX_EPISODIC_ENTRIES) {
            episodicStore.remove(0);
        }
        persistMemory();
        String key = wmKey(userId, conversationId);
        try {
            String oldSummary = safeRedisGet(summaryKey(userId, conversationId));
            redisTemplate.opsForValue().set(summaryKey(userId, conversationId), ((oldSummary == null ? "" : oldSummary + "\n") + summary).trim(),
                    Duration.ofSeconds(properties.getMemory().getTtlSeconds()));
            // 用 LTRIM 原地裁掉旧消息：表头（index 0）本来就是最新消息，
            // 保留 0..keep-1 即“最近 keep 条”，顺序天然正确且操作原子。
            // 旧实现 delete + 倒序 leftPush 会把列表顺序整体写反，
            // 且 delete 与重推之间进程崩溃会清空整个会话的工作记忆。
            redisTemplate.opsForList().trim(key, 0, keep - 1);
            redisTemplate.expire(key, Duration.ofSeconds(properties.getMemory().getTtlSeconds()));
        } catch (Exception ex) {
            log.warn("Memory compression failed: {}", ex.getMessage());
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
        double[] queryVec = embeddingService.embed(query);
        return episodicStore.stream()
                .filter(e -> e.userId().equals(userId))
                .filter(e -> embeddingService.cosine(queryVec, e.embedding()) >= EPISODIC_MIN_SIMILARITY)
                .sorted(Comparator.comparingDouble((EpisodicEntry e) -> embeddingService.cosine(queryVec, e.embedding())).reversed())
                .limit(5)
                .map(EpisodicEntry::summary)
                .toList();
    }

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
                                embeddingService.embed(entry.summary())
                        ));
                    }
                });
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
                    .map(entry -> new StoredEpisodicEntry(entry.userId(), entry.conversationId(), entry.summary(), entry.fullText(), entry.timestamp()))
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

    private record EpisodicEntry(String userId, String conversationId, String summary, String fullText, Instant timestamp, double[] embedding) {
    }

    private record StoredMemory(List<StoredEpisodicEntry> episodic, Map<String, Map<String, Object>> profiles) {
        private StoredMemory {
            episodic = episodic == null ? List.of() : episodic;
            profiles = profiles == null ? Map.of() : profiles;
        }
    }

    private record StoredEpisodicEntry(String userId, String conversationId, String summary, String fullText, Instant timestamp) {
    }
}
