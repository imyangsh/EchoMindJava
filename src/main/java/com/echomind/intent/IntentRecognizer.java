package com.echomind.intent;

import com.echomind.config.EchoMindProperties;
import com.echomind.agent.ExecutionContext;
import com.echomind.llm.LlmGateway;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LLM、语义与规则三通道识别；明确域外结论拒识，证据不足时交由上层澄清。
 * 缓存使用线程安全 LRU 与 TTL，键包含用户、会话、原始消息和完整模型提示词。
 */
@Service
public class IntentRecognizer {

    private static final Logger log = LoggerFactory.getLogger(IntentRecognizer.class);

    /** 决策级联里各通道的来源标识，会写进 IntentResult.source。 */
    public static final String SOURCE_LLM = "llm";
    public static final String SOURCE_SEMANTIC = "semantic";
    public static final String SOURCE_PATTERN = "pattern";
    public static final String SOURCE_ABSTAIN = "abstain";

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper;
    private final IntentSampleStore sampleStore;
    private final IntentVectorIndex vectorIndex;
    private final EntityExtractor entityExtractor;
    private final EchoMindProperties properties;
    private final Map<CacheKey, CacheEntry> cache;
    private final Clock clock;
    private final IntentPatternMatcher patternMatcher = new IntentPatternMatcher();

    @Autowired
    public IntentRecognizer(LlmGateway llmGateway,
                            ObjectMapper objectMapper,
                            IntentSampleStore sampleStore,
                            IntentVectorIndex vectorIndex,
                            EntityExtractor entityExtractor,
                            EchoMindProperties properties) {
        this(llmGateway, objectMapper, sampleStore, vectorIndex, entityExtractor, properties, Clock.systemUTC());
    }

    IntentRecognizer(LlmGateway llmGateway, ObjectMapper objectMapper, IntentSampleStore sampleStore,
                     IntentVectorIndex vectorIndex, EntityExtractor entityExtractor, EchoMindProperties properties, Clock clock) {
        this.llmGateway = llmGateway;
        this.objectMapper = objectMapper;
        this.sampleStore = sampleStore;
        this.vectorIndex = vectorIndex;
        this.entityExtractor = entityExtractor;
        this.properties = properties;
        this.clock = clock;
        this.cache = lruCache(properties.getIntent().getCacheMaxEntries());
    }

    /**
     * 带用户与会话维度的识别入口。缓存键必须包含二者：同一句"怎么弄"在不同用户的
     * 不同会话里指代完全不同，只按消息文本缓存会把 A 用户的语境结果串给 B 用户。
     */
    public IntentResult recognize(String message, String userId, String conversationId, List<Map<String, String>> history) {
        return recognizeDetailed(message, userId, conversationId, history).result();
    }

    /** 返回完整过程快照（三通道证据 + 失败归因 + 缓存命中），供 /chat 响应与排查使用。 */
    public RecognitionOutcome recognizeDetailed(String message, String userId, String conversationId, List<Map<String, String>> history) {
        String prompt = buildPrompt(message, history);
        CacheKey key = new CacheKey(userId, conversationId, message, prompt, sampleStore.revision());
        CacheEntry entry = properties.getIntent().getCacheTtlSeconds() > 0 ? cache.get(key) : null;
        if (entry != null && entry.expiresAt().isAfter(clock.instant())) {
            RecognitionOutcome cached = entry.outcome();
            // 缓存整个 outcome 而不是只缓存 result：命中时同样要能回答"当时是哪条通道定的、
            // 次意图是什么"，否则缓存命中与未命中两条路径给出的可观测信息不一致。
            return new RecognitionOutcome(cached.result(), cached.llm(), cached.semantic(), cached.pattern(),
                    cached.llmFailure(), true, cached.matchedIntents());
        }
        RecognitionOutcome outcome = recognizeWithPrompt(message, prompt);
        int ttl = properties.getIntent().getCacheTtlSeconds();
        if (ttl > 0 && outcome.llmFailure().isBlank() && !vectorIndex.degraded(outcome.semantic())) {
            cache.put(key, new CacheEntry(outcome, clock.instant().plusSeconds(ttl)));
        }
        return outcome;
    }

    /**
     * 绕过缓存的识别入口，供评测与排查使用。
     *
     * <p>评测必须走这里：在线路径为了省钱省延迟而缓存，同一句消息重复出现会直接返回
     * 上一次的结果，用它算准确率等于在测缓存而不是测识别能力，指标会虚高。
     */
    public RecognitionOutcome recognizeUncached(String message, List<Map<String, String>> history) {
        return recognizeWithPrompt(message, buildPrompt(message, history));
    }

    private RecognitionOutcome recognizeWithPrompt(String message, String prompt) {
        Instant start = Instant.now();
        PatternEvidence pattern = patternRecognize(message);
        LlmAttempt attempt = llmRecognize(prompt);
        if (ExecutionContext.current() != null) ExecutionContext.current().checkActive();
        SemanticResolution semantic = semanticRecognize(message).excluding(pattern.negatedIntents());
        Resolution resolution = decide(message, attempt.match(), attempt.failure(), semantic, pattern.match());
        IntentResult result = new IntentResult(
                resolution.intent(),
                resolution.confidence(),
                urgency(message, resolution.intent()),
                entityExtractor.extract(message),
                resolution.reasoning(),
                Duration.between(start, Instant.now()).toMillis(),
                resolution.source(),
                resolution.abstained(),
                resolution.abstained() ? List.of() : secondaryIntents(resolution.intent(), semantic, pattern)
        );
        return new RecognitionOutcome(result, attempt.match(), semantic, pattern.match(), attempt.failure(), false, pattern.matchedIntents());
    }

    /**
     * 本条消息在模式层命中的全部领域意图（不只最终胜出的那个）。
     *
     * <p>旧实现里 AgentOrchestrator 自带一套硬编码关键词表来判断"要不要拉上技术/账单 Agent"，
     * 与 IntentRecognizer 的模式词表是两份互相不一致的规则。这里把领域证据统一暴露出来，
     * 让协作路由复用同一份可配置词表。
     */
    public Set<IntentCategory> domainIntents(RecognitionOutcome outcome) {
        if (outcome == null || outcome.result().abstained()) {
            return Set.of();
        }
        Set<IntentCategory> domains = new LinkedHashSet<>();
        domains.add(outcome.result().intent());
        domains.addAll(outcome.result().secondaryIntents());
        domains.remove(IntentCategory.OTHER);
        return domains;
    }

    /** 次意图保留强语义且有多个样本支持的候选，以及有效规则命中的其他领域。 */
    private List<IntentCategory> secondaryIntents(IntentCategory primary, SemanticResolution semantic, PatternEvidence pattern) {
        LinkedHashSet<IntentCategory> secondary = new LinkedHashSet<>();
        semantic.candidatesAbove(properties.getIntent().getSemanticThreshold())
                .stream().filter(candidate -> candidate.support() >= 2)
                .filter(candidate -> !pattern.negatedIntents().contains(candidate.intent()))
                .forEach(candidate -> secondary.add(candidate.intent()));
        secondary.addAll(pattern.matchedIntents());
        secondary.remove(primary);
        secondary.remove(IntentCategory.OTHER);
        return List.copyOf(secondary);
    }

    // ---------------------------------------------------------------- LLM 通道

    /**
     * 调用 LLM 做零样本/少样本分类。
     *
     * <p>用 {@code chatStructured} 而不是 {@code chat}：后者的自然语言 fallback 会在
     * 网关故障时返回一段客服口吻的中文散文，意图通道收到这种"回答"后既解析不出 JSON、
     * 又不会触发失败标记（因为没抛异常），最终静默退化为 OTHER 且日志里查不到原因。
     */
    private LlmAttempt llmRecognize(String prompt) {
        String raw;
        try {
            ExecutionContext execution = ExecutionContext.current();
            if (execution != null) {
                execution.checkActive();
                execution.recordLlmCall();
                execution.recordLlmUsage(-1, -1);
            }
            raw = llmGateway.chatStructured(prompt, 0.1, 256);
        } catch (Exception ex) {
            String failure = classifyFailure(ex);
            log.warn("Intent LLM channel failed ({}): {}", failure, ex.getMessage());
            return new LlmAttempt(ChannelMatch.abstain(), failure);
        }
        if (raw == null || raw.isBlank()) {
            return new LlmAttempt(ChannelMatch.abstain(), "llm_empty_response");
        }
        String json = sliceJsonObject(raw);
        if (json == null) {
            return new LlmAttempt(ChannelMatch.abstain(), "llm_invalid_json");
        }
        Map<String, Object> data;
        try {
            data = objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.warn("Intent LLM channel returned unparsable JSON: {}", abbreviate(raw));
            return new LlmAttempt(ChannelMatch.abstain(), "llm_invalid_json");
        }
        if (!(data.get("intent") instanceof String label) || !data.containsKey("confidence")) {
            return new LlmAttempt(ChannelMatch.abstain(), "llm_invalid_schema");
        }
        IntentCategory intent;
        try {
            intent = IntentCategory.valueOf(label.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidLabel) {
            return new LlmAttempt(ChannelMatch.abstain(), "llm_unknown_intent");
        }
        double confidence = num(data, "confidence");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            return new LlmAttempt(ChannelMatch.abstain(), "llm_invalid_schema");
        }
        String reasoning = value(data, "reasoning");
        if (intent == IntentCategory.OTHER && confidence <= 0.0) {
            // 模型显式回答 other 是有效结论（域外问题），仍需按弃权处理，让语义通道有机会补位
            return new LlmAttempt(new ChannelMatch(IntentCategory.OTHER, 0.0, reasoning, 0, 0, ""), "");
        }
        return new LlmAttempt(new ChannelMatch(intent, confidence, reasoning, 0, 0, ""), "");
    }

    private String buildPrompt(String message, List<Map<String, String>> history) {
        String examples = fewShotExamples();
        String recent = history == null || history.isEmpty() ? "（无）" : renderHistory(history);
        return """
                你是客服意图分析专家。只输出一个 JSON 对象，不要输出任何解释或 Markdown 代码块。

                可选意图: query, complaint, request, greeting, escalation, technical, billing, account, feedback, other
                边界说明:
                - query: 只想获取信息（怎么/如何/什么时候/多久/为什么）
                - request: 要求系统执行动作（帮我取消/修改/申请）
                - complaint: 对已发生体验表达不满，但不要求转人工
                - escalation: 明确要求转人工、找经理/主管，或声明要投诉
                - technical: 报错、崩溃、无法登录等产品故障
                - billing: 扣款、退款、账单、发票、支付
                - account: 密码、邮箱、手机号、注销等账户资料
                - feedback: 正面评价或致谢
                - other: 闲聊、域外问题或无法判断

                示例:
                %s
                最近对话:
                %s
                用户消息: "%s"

                返回格式: {"intent":"technical","confidence":0.9,"reasoning":"一句话说明"}
                """.formatted(examples, recent, sanitize(message));
    }

    /**
     * 每个意图取最多 3 条样本作为 few-shot。旧实现只取 {@code samples.getFirst()}，
     * JSON 里 5/6 的标注数据（含最难分的边界样本）完全没进提示词。
     */
    private String fewShotExamples() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<IntentCategory, List<String>> entry : sampleStore.samples().entrySet()) {
            List<String> samples = entry.getValue();
            int limit = Math.min(3, samples.size());
            for (int i = 0; i < limit; i++) {
                builder.append("消息: \"").append(sanitize(samples.get(i)))
                        .append("\" -> 意图: ").append(entry.getKey().name().toLowerCase(Locale.ROOT))
                        .append('\n');
            }
        }
        return builder.toString();
    }

    private String renderHistory(List<Map<String, String>> history) {
        int from = Math.max(0, history.size() - properties.getIntent().getCacheHistoryTurns());
        StringBuilder builder = new StringBuilder();
        for (Map<String, String> turn : history.subList(from, history.size())) {
            builder.append("- ").append(turn.getOrDefault("role", "user"))
                    .append(": ").append(sanitize(turn.getOrDefault("content", "")))
                    .append('\n');
        }
        return builder.toString();
    }

    /**
     * 用户文本直接插值进提示词会被"忽略以上指令"之类的输入操纵。
     * 这里压掉换行与双引号，至少让攻击者无法闭合引号、伪造新的"消息->意图"样例行。
     */
    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("[\\r\\n]+", " ").replace('"', '\'').replace("→", "->");
        return cleaned.length() > 500 ? cleaned.substring(0, 500) + "..." : cleaned;
    }

    /**
     * 失败归因。需要递归看 cause：超时/连接异常常被 Spring 的 RestClient 包成
     * ResourceAccessException 之类的运行时异常，只判断最外层类型会把超时误报成"不可用"。
     */
    private String classifyFailure(Exception ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof java.util.concurrent.TimeoutException) {
                return "llm_timeout";
            }
            String name = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(Locale.ROOT);
            if (name.contains("timeout") || message.contains("timed out") || message.contains("timeout")) {
                return "llm_timeout";
            }
        }
        return "llm_unavailable";
    }

    // ---------------------------------------------------------------- 语义通道

    private SemanticResolution semanticRecognize(String message) {
        if (!properties.getIntent().isSemanticEnabled()) {
            return SemanticResolution.empty();
        }
        try {
            return vectorIndex.nearest(message, properties.getIntent().getSemanticTopK());
        } catch (Exception ex) {
            log.warn("Intent semantic channel failed: {}", ex.getMessage());
            return SemanticResolution.empty();
        }
    }

    // ---------------------------------------------------------------- 模式通道

    /** 按命中位置去除重叠与重复词，过滤否定表达，并区分内容词和泛化词。 */
    private PatternEvidence patternRecognize(String message) {
        IntentPatternMatcher.Evidence evidence = patternMatcher.match(message, properties.getIntent().getPatterns());
        return new PatternEvidence(evidence.strongest(), evidence.active(), evidence.negated());
    }

    private record PatternEvidence(ChannelMatch match, Set<IntentCategory> matchedIntents,
                                   Set<IntentCategory> negatedIntents) { }

    private static final double EXPLAIN_RATIO = 0.22;

    private Resolution decide(String message, ChannelMatch llm, String llmFailure, SemanticResolution semantic, ChannelMatch pattern) {
        EchoMindProperties.Intent config = properties.getIntent();

        if (llmFailure.isBlank() && llm.intent() == IntentCategory.OTHER && llm.confidence() >= config.getLlmDirectThreshold()) {
            return Resolution.of(IntentCategory.OTHER, llm.confidence(), SOURCE_LLM, reason(llm, llmFailure) + " (out of domain)", true);
        }

        // 1) LLM 高置信：直接采信。它见过完整语境，比样本相似度可靠
        if (llm.asserts() && llm.confidence() >= config.getLlmDirectThreshold()) {
            return Resolution.of(llm.intent(), llm.confidence(), SOURCE_LLM, reason(llm, llmFailure), false);
        }

        boolean llmUncertain = !llm.asserts() || llm.confidence() < config.getMinConfidence();
        if (llmUncertain && semantic.best() == IntentCategory.OTHER
                && semantic.score() >= config.getSemanticThreshold() && semantic.margin() >= config.getSemanticMargin()) {
            return Resolution.of(IntentCategory.OTHER, semantic.score(), SOURCE_SEMANTIC, semanticReason(semantic) + " (out of domain)", true);
        }
        boolean semanticStrong = semanticStrong(semantic, config);
        boolean semanticCounts = llmUncertain && semanticHasSignal(semantic, config) && semantic.margin() >= config.getWeakMargin();
        // 必须至少有一个"内容词"命中：否则"帮我解绑微信"这类句子仅凭"帮我"就会定夺意图
        boolean patternStrong = pattern.contentHits() >= config.getPatternMinHits();

        // 2) 语义强证据：仅当 LLM 弃权或低置信时才允许生效，且必须同时满足阈值与 margin。
        //    排在模式通道之前——"钱什么时候退回原支付账户"与 billing 样本逐字相同（相似度 1.000），
        //    同时又命中了 billing 关键词"退回"，此时应当归因给更有说服力的语义通道而不是模式通道。
        if (llmUncertain && semanticStrong) {
            return semanticResolution(semantic);
        }

        // 3) 模式通道达到最少命中数：确定性、可审计，优先于语义弱证据
        if (patternStrong) {
            return Resolution.of(pattern.intent(), pattern.confidence(), SOURCE_PATTERN,
                    "matched keywords: " + pattern.detail(), false);
        }

        // 4) 单内容词与语义方向一致时，由规则提供主证据；相似度无需达到独立路由阈值。
        //    明确业务词也不能被弱 greeting/feedback 相似度覆盖，高置信 LLM 和强语义仍优先。
        if (pattern.contentHits() > 0) {
            if (semantic.best() == pattern.intent() && semantic.score() > 0.0) {
                // 语义已过 semantic-threshold 且与模式一致时归因给语义：
                // 同样是 billing，"逐字命中样本（1.000）"比"命中关键词（0.500）"更有说服力
                if (semanticStrong) {
                    return semanticResolution(semantic);
                }
                return Resolution.of(pattern.intent(), pattern.confidence(), SOURCE_PATTERN,
                        "matched keywords: " + pattern.detail() + " (corroborated by semantic)", false);
            }
            if (semanticCounts && weakConversationConflictsWithBusinessKeyword(semantic, pattern)) {
                return Resolution.of(pattern.intent(), pattern.confidence(), SOURCE_PATTERN,
                        "matched keywords: " + pattern.detail() + " (business keyword outweighs weak conversational similarity)", false);
            }
            if (!semanticCounts && explainsEnough(pattern, message)) {
                return Resolution.of(pattern.intent(), pattern.confidence(), SOURCE_PATTERN,
                        "matched keywords: " + pattern.detail() + " (single hit, semantic inconclusive)", false);
            }
        }

        // 5) 语义弱证据：分数没到 semantic-threshold，但与次优拉开了足够距离。
        //    这一档来自实测：hash 兜底向量下复述句的绝对相似度普遍只有 0.15~0.45，
        //    但 top1 往往正是正确类别（如"运费是怎么算的"sem=0.408/0.144）。只认绝对阈值会让
        //    整个兜底路径在 LLM 不可用时几乎全线弃权。
        if (semanticCounts) {
            return Resolution.of(semantic.best(), semantic.score(), SOURCE_SEMANTIC,
                    semanticReason(semantic) + " (weak evidence)", false);
        }

        // 6) LLM 达到最低接受置信度时保留结果；低于该值且无其他证据则澄清。
        if (llm.asserts() && llm.confidence() >= config.getMinConfidence()) {
            return Resolution.of(llm.intent(), llm.confidence(), SOURCE_LLM, reason(llm, llmFailure) + " (low confidence)", false);
        }

        // 7) 三通道均无有效证据：拒识，交由上层澄清，而不是硬猜一个类别
        return Resolution.of(IntentCategory.OTHER, 0.0, SOURCE_ABSTAIN,
                "abstained: " + (llmFailure.isBlank() ? "no channel produced sufficient evidence" : llmFailure), true);
    }

    /** 语义通道胜出时的统一出口，保证归因与理由格式一致。 */
    private Resolution semanticResolution(SemanticResolution semantic) {
        return Resolution.of(semantic.best(), semantic.score(), SOURCE_SEMANTIC, semanticReason(semantic), false);
    }

    /** 语义通道独立定夺的条件：分数达阈值且与次优拉得开。 */
    private boolean semanticStrong(SemanticResolution semantic, EchoMindProperties.Intent config) {
        return semantic.best() != IntentCategory.OTHER
                && semantic.score() >= config.getSemanticThreshold()
                && semantic.margin() >= config.getSemanticMargin();
    }

    /** 语义通道是否给出了可用信号：top1 是真实意图且分数达到了弱证据下限。 */
    private boolean semanticHasSignal(SemanticResolution semantic, EchoMindProperties.Intent config) {
        return semantic.best() != IntentCategory.OTHER
                && semantic.score() >= config.getWeakScore();
    }

    private boolean weakConversationConflictsWithBusinessKeyword(SemanticResolution semantic, ChannelMatch pattern) {
        Set<IntentCategory> conversational = Set.of(IntentCategory.GREETING, IntentCategory.FEEDBACK);
        return conversational.contains(semantic.best()) && !conversational.contains(pattern.intent())
                && java.util.Arrays.stream(pattern.contentText().split(",")).anyMatch(keyword -> keyword.length() >= 2);
    }

    /**
     * 单个内容词是否足以解释整条消息。
     *
     * <p>只看内容词（不看"帮我""麻烦"这类泛化词）：单独一个泛化词永远不能定夺意图，
     * 否则"帮我查一下物流进度"会因为"帮我"被判成 request、而不是它本该是的 query。
     *
     * <p>无可用语义信号时，要求内容词覆盖消息的至少 22%，避免长句中偶然出现一个词就独立路由。
     */
    private boolean explainsEnough(ChannelMatch pattern, String message) {
        // 去掉空白再算长度：否则空格会稀释覆盖率
        String normalized = normalize(message).replaceAll("\\s+", "");
        if (normalized.isEmpty()) {
            return false;
        }
        int matchedChars = 0;
        for (String keyword : pattern.contentText().split(",")) {
            matchedChars += keyword.replaceAll("\\s+", "").length();
        }
        return (double) matchedChars / normalized.length() >= EXPLAIN_RATIO;
    }

    private String reason(ChannelMatch llm, String failure) {
        if (!failure.isBlank()) {
            return failure;
        }
        return llm.detail().isBlank() ? "llm classification" : llm.detail();
    }

    private String semanticReason(SemanticResolution semantic) {
        return "semantic sample match (score=" + round(semantic.score())
                + ", margin=" + round(semantic.margin())
                + ", sample=\"" + abbreviate(semantic.detail()) + "\")";
    }

    private record LlmAttempt(ChannelMatch match, String failure) {
    }

    private record Resolution(IntentCategory intent, double confidence, String source, String reasoning, boolean abstained) {
        static Resolution of(IntentCategory intent, double confidence, String source, String reasoning, boolean abstained) {
            return new Resolution(intent, confidence, source, reasoning, abstained);
        }
    }

    // ---------------------------------------------------------------- 基础设施

    /**
     * 缓存键保留原始消息与完整模型提示词，样本版本变化后不复用旧识别结果。
     */
    private record CacheKey(String userId, String conversationId, String message, String prompt, long sampleRevision) { }

    private record CacheEntry(RecognitionOutcome outcome, Instant expiresAt) { }

    private static Map<CacheKey, CacheEntry> lruCache(int maxEntries) {
        return Collections.synchronizedMap(new LinkedHashMap<CacheKey, CacheEntry>(Math.min(256, maxEntries), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, CacheEntry> eldest) {
                return size() > maxEntries;
            }
        });
    }

    /**
     * 安全提取数值：LLM 返回 {"confidence":"0.9"}（字符串）或 {"confidence":null}
     * 都是完全可能的合法 JSON，旧实现的 (Number) 强转在这两种情况下分别抛
     * ClassCastException 与 NPE，直接把 /chat 打成 500。
     */
    private double num(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private String value(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        return raw == null ? "" : String.valueOf(raw);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String single = value.replaceAll("\\s+", " ").trim();
        return single.length() > 160 ? single.substring(0, 160) + "..." : single;
    }

    private UrgencyLevel urgency(String message, IntentCategory intent) {
        String msg = normalize(message);
        if (msg.contains("紧急") || msg.contains("urgent") || msg.contains("立刻")) {
            return UrgencyLevel.CRITICAL;
        }
        if (msg.contains("今天") || msg.contains("马上") || msg.contains("尽快") || intent == IntentCategory.ESCALATION) {
            return UrgencyLevel.HIGH;
        }
        if (intent == IntentCategory.COMPLAINT) {
            return UrgencyLevel.MEDIUM;
        }
        return UrgencyLevel.LOW;
    }

    /**
     * 从可能带 Markdown 代码块或前后缀说明的响应里切出 JSON 对象。
     * 旧实现"找不到花括号就返回 {}"，把"没返回 JSON"这个明确的故障伪装成
     * "返回了空对象"，调用方无法区分；这里改为返回 null，交给上层记为 llm_invalid_json。
     */
    private String sliceJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
