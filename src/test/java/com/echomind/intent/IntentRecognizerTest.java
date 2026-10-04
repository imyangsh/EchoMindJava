package com.echomind.intent;

import com.echomind.config.EchoMindProperties;
import com.echomind.knowledge.TextEmbeddingService;
import com.echomind.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentRecognizerTest {

    private static final String REFUND_SAMPLE_VERBATIM = "钱什么时候退回原支付账户";

    @Test
    void semanticSampleMatchCanWinWhenLlmIsUncertain() {
        EchoMindProperties properties = new EchoMindProperties();
        IntentRecognizer recognizer = recognizer(properties, llm("""
                {"intent":"other","confidence":0.0,"reasoning":"uncertain"}
                """));

        IntentResult result = recognizer.recognize(REFUND_SAMPLE_VERBATIM, null, "u1", List.of());

        assertThat(result.intent()).isEqualTo(IntentCategory.BILLING);
        assertThat(result.source()).isEqualTo(IntentRecognizer.SOURCE_SEMANTIC);
        assertThat(result.abstained()).isFalse();
        //旧实现在语义通道胜出时仍返回 LLM 的 0.0 置信度与 "uncertain" 理由
        assertThat(result.confidence()).isGreaterThan(0.9);
        assertThat(result.reasoning()).contains("semantic");
    }

    /**
     * 旧实现把三通道置信度乘权重相加再比 0.35，LLM 自报 0.9 时总分 0.63 恰好过线，
     * 但报告的却是"胜出通道原始置信度"；这里固化新语义：高置信 LLM 直采且置信度原样透出。
     */
    @Test
    void highConfidenceLlmIsTakenDirectly() {
        IntentRecognizer recognizer = recognizer(new EchoMindProperties(), llm("""
                {"intent":"technical","confidence":0.9,"reasoning":"crash report"}
                """));

        IntentResult result = recognizer.recognize("页面一直转圈打不开", null, "u1", List.of());

        assertThat(result.intent()).isEqualTo(IntentCategory.TECHNICAL);
        assertThat(result.source()).isEqualTo(IntentRecognizer.SOURCE_LLM);
        assertThat(result.confidence()).isEqualTo(0.9);
        assertThat(result.reasoning()).isEqualTo("crash report");
    }

    /**
     * LLM 低于最低接受置信度，且没有语义或规则支持时，返回澄清。
     */
    @Test
    void weakLlmAssertionRequiresClarificationWhenNoOtherChannelSupportsIt() {
        IntentRecognizer recognizer = recognizer(new EchoMindProperties(), llm("""
                {"intent":"query","confidence":0.2,"reasoning":"possibly asking"}
                """));

        IntentResult result = recognizer.recognize("关于上次那个事情", null, "u1", List.of());

        assertThat(result.intent()).isEqualTo(IntentCategory.OTHER);
        assertThat(result.source()).isEqualTo(IntentRecognizer.SOURCE_ABSTAIN);
        assertThat(result.abstained()).isTrue();
    }

    @Test
    void abstainsWhenNoChannelHasEvidence() {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getIntent().setSemanticEnabled(false);
        IntentRecognizer recognizer = recognizer(properties, llm("""
                {"intent":"other","confidence":0.0,"reasoning":"nonsense"}
                """));

        IntentResult result = recognizer.recognize("asdfghjkl", null, "u1", List.of());

        assertThat(result.intent()).isEqualTo(IntentCategory.OTHER);
        assertThat(result.abstained()).isTrue();
        assertThat(result.source()).isEqualTo(IntentRecognizer.SOURCE_ABSTAIN);
        assertThat(result.confidence()).isZero();
    }

    /**
     * 旧实现用 catch (Exception) 把"网关抛异常"和"返回的不是 JSON"合并成同一条 failed 标记。
     * 归因不同意味着排查方向完全不同，必须分开。
     */
    @Test
    void attributesLlmGatewayFailureToUnavailable() {
        IntentRecognizer recognizer = recognizer(new EchoMindProperties(),
                (systemPrompt, userPrompt, temperature, maxTokens) -> {
                    throw new IllegalStateException("No real API key configured for active LLM profile: anthropic");
                });

        RecognitionOutcome outcome = recognizer.recognizeUncached(REFUND_SAMPLE_VERBATIM, List.of());

        assertThat(outcome.llmFailure()).isEqualTo("llm_unavailable");
        //LLM 完全不可用时语义通道仍应给出结论，而不是把整轮打成 OTHER
        assertThat(outcome.result().intent()).isEqualTo(IntentCategory.BILLING);
        assertThat(outcome.result().source()).isEqualTo(IntentRecognizer.SOURCE_SEMANTIC);
    }

    @Test
    void attributesTimeoutSeparatelyFromUnavailable() {
        IntentRecognizer recognizer = recognizer(new EchoMindProperties(),
                (systemPrompt, userPrompt, temperature, maxTokens) -> {
                    //LlmGateway.chat 未声明受检异常，用运行时异常承载超时语义
                    throw new java.util.concurrent.CompletionException(
                            new java.util.concurrent.TimeoutException("read timed out after 60s"));
                });

        RecognitionOutcome outcome = recognizer.recognizeUncached(REFUND_SAMPLE_VERBATIM, List.of());

        assertThat(outcome.llmFailure()).isEqualTo("llm_timeout");
    }

    /**
     * 网关的 {@code chat} 在 fallback-enabled 时会返回客服口吻的中文散文。
     * 旧实现收到这种响应后 sliceJsonObject 返回 "{}"，既不抛异常也不置失败标记，
     * 最终静默退化为 OTHER 且日志里查不到原因。现在必须记为 llm_invalid_json。
     */
    @Test
    void attributesNonJsonResponseToInvalidJson() {
        IntentRecognizer recognizer = recognizer(new EchoMindProperties(),
                llm("根据当前知识库，退款通常需要先提交申请并等待审核。请提供订单号。"));

        RecognitionOutcome outcome = recognizer.recognizeUncached(REFUND_SAMPLE_VERBATIM, List.of());

        assertThat(outcome.llmFailure()).isEqualTo("llm_invalid_json");
        //语义通道照样能兜住这句逐字匹配的样本
        assertThat(outcome.result().intent()).isEqualTo(IntentCategory.BILLING);
    }

    @Test
    void acceptsStringConfidenceAndUnknownIntentLabel() {
        EchoMindProperties properties = new EchoMindProperties();
        //LLM 返回字符串置信度与未知意图：旧实现的 (Number) 强转会抛 ClassCastException，把 /chat 打成 500
        IntentRecognizer numericAsString = recognizer(properties, llm("""
                {"intent":"billing","confidence":"0.9","reasoning":"quoted number"}
                """));
        assertThat(numericAsString.recognizeUncached("随便一句话", List.of()).result().confidence()).isEqualTo(0.9);

        IntentRecognizer unknownLabel = recognizer(new EchoMindProperties(), llm("""
                {"intent":"refund_request","confidence":0.9,"reasoning":"unknown label"}
                """));
        RecognitionOutcome outcome = unknownLabel.recognizeUncached("随便一句话", List.of());
        assertThat(outcome.result().intent()).isEqualTo(IntentCategory.OTHER);
        //未知标签被归一为 OTHER 后等同弃权，不能再冒充"高置信度的 LLM 结论"
        assertThat(outcome.result().source()).isEqualTo(IntentRecognizer.SOURCE_ABSTAIN);
        assertThat(outcome.llmFailure()).isEqualTo("llm_unknown_intent");
    }

    /**
     * 旧实现要求命中数/词表长度 >= 0.35，而模式通道权重只有 0.10，即使命中 3 个关键词
     * 也只贡献 0.075，永远无法独立成立。现在按最少命中数独立定夺。
     */
    @Test
    void patternChannelCanStandAloneWhenLlmAbstains() {
        EchoMindProperties properties = new EchoMindProperties();
        //关掉语义通道，隔离验证"模式通道不需要 LLM 与语义也能独立定夺"
        properties.getIntent().setSemanticEnabled(false);
        IntentRecognizer recognizer = recognizer(properties, llm("""
                {"intent":"other","confidence":0.0,"reasoning":"uncertain"}
                """));

        IntentResult result = recognizer.recognize("我要投诉，转人工客服", null, "u1", List.of());

        assertThat(result.intent()).isEqualTo(IntentCategory.ESCALATION);
        assertThat(result.source()).isEqualTo(IntentRecognizer.SOURCE_PATTERN);
    }

    /**
     * 单独一个"泛化词"不构成路由依据。
     *
     * <p>"帮我"只表示"在请求"，不携带任何领域信息。旧实现里它和"解绑""退款"同权重，
     * "帮我查一下物流进度"会因为"帮我"被拉到 request（正确答案是 query）。
     */
    @Test
    void singleFunctionWordIsNotEnoughToRoute() {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getIntent().setSemanticEnabled(false);
        IntentRecognizer recognizer = recognizer(properties, llm("""
                {"intent":"other","confidence":0.0,"reasoning":"uncertain"}
                """));

        //只命中泛化词"帮我"，关掉语义与 LLM 后应当拒识而不是猜成 request
        IntentResult result = recognizer.recognize("帮我 zzz", null, "u1", List.of());

        assertThat(result.abstained()).isTrue();
    }

    /**
     * 但泛化词 + 内容词同时命中时应当正常路由：这里 request 命中"帮我"（泛化）与"取消订单"（内容）。
     */
    @Test
    void functionWordPlusContentWordRoutes() {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getIntent().setSemanticEnabled(false);
        IntentRecognizer recognizer = recognizer(properties, llm("""
                {"intent":"other","confidence":0.0,"reasoning":"uncertain"}
                """));

        IntentResult result = recognizer.recognize("帮我取消订单", null, "u1", List.of());

        assertThat(result.intent()).isEqualTo(IntentCategory.REQUEST);
        assertThat(result.source()).isEqualTo(IntentRecognizer.SOURCE_PATTERN);
    }

    /**
     * query 词表旧实现含裸 "?"/"？"，任何带问号的消息都白送 query 权重。
     * 这里确认问号不再单独构成 query 证据。
     */
    @Test
    void bareQuestionMarkIsNotQueryEvidence() {
        IntentRecognizer recognizer = recognizer(new EchoMindProperties(), llm("""
                {"intent":"other","confidence":0.0,"reasoning":"uncertain"}
                """));

        RecognitionOutcome outcome = recognizer.recognizeUncached("zzz？", List.of());

        assertThat(outcome.pattern().asserts()).isFalse();
    }

    /**
     * 旧缓存键为 message + "|h:" + history.hashCode()，不含 userId/conversationId，
     * 两个用户的相同消息会命中同一条结果。这里用计数网关验证第二次必须重新识别。
     */
    @Test
    void cacheDoesNotLeakAcrossUsers() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        LlmGateway counting = (systemPrompt, userPrompt, temperature, maxTokens) -> {
            calls.incrementAndGet();
            return "{\"intent\":\"billing\",\"confidence\":0.9,\"reasoning\":\"x\"}";
        };
        IntentRecognizer recognizer = recognizer(new EchoMindProperties(), counting);
        String message = "为什么扣了两次款";
        List<Map<String, String>> history = noHistory();

        recognizer.recognizeDetailed(message, "user-A", "conv-1", history);
        int afterFirst = calls.get();
        recognizer.recognizeDetailed(message, "user-A", "conv-1", history);
        assertThat(calls.get())
                .as("同一用户同一会话的重复消息应命中缓存")
                .isEqualTo(afterFirst);
        recognizer.recognizeDetailed(message, "user-B", "conv-1", history);
        assertThat(calls.get())
                .as("不同用户必须重新识别，不能串结果")
                .isEqualTo(afterFirst + 1);
    }

    @Test
    void extractsTypedEntities() {
        IntentRecognizer recognizer = recognizer(new EchoMindProperties(), llm("""
                {"intent":"billing","confidence":0.9,"reasoning":"x"}
                """));

        IntentResult result = recognizer.recognize("订单号 #12345 扣了 50 元，报错 500，电话 13800138000，今天要处理",
                null, "u1", List.of());

        assertThat(result.entities()).containsEntry("order_id", List.of("12345"));
        assertThat(result.entities()).containsEntry("error_code", List.of("500"));
        assertThat(result.entities()).containsEntry("amount", List.of("50"));
        assertThat(result.entities()).containsEntry("phone", List.of("13800138000"));
        assertThat(result.entities()).containsEntry("date", List.of("今天"));
    }

    /** 纯数字流水/时间戳不应被误判成订单号——旧正则 #?\d{5,} 会把它们一并吞下。 */
    @Test
    void plainDigitsAreNotTreatedAsOrderId() {
        IntentRecognizer recognizer = recognizer(new EchoMindProperties(), llm("""
                {"intent":"other","confidence":0.0,"reasoning":"x"}
                """));

        IntentResult result = recognizer.recognize("20240101 这个编号是什么意思", null, "u1", List.of());

        assertThat(result.entities()).doesNotContainKey("order_id");
    }

    @Test
    void entityExtractorIgnoresBlankAndNullInput() {
        EntityExtractor extractor = new EntityExtractor();
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract("   ")).isEmpty();
        assertThat(extractor.extract("订单 12345")).containsEntry("order_id", List.of("12345"));
    }

    /** 单领域消息的次意图不应把无关领域拉进来，否则协作路由会为简单问题多跑一个 Agent。 */
    @Test
    void singleDomainMessageHasNoUnrelatedSecondaryIntent() {
        EchoMindProperties properties = new EchoMindProperties();
        properties.getIntent().setSemanticEnabled(false);
        IntentRecognizer recognizer = recognizer(properties, llm("""
                {"intent":"other","confidence":0.0,"reasoning":"uncertain"}
                """));

        IntentResult result = recognizer.recognize("应用一直报500错误", null, "u1", List.of());

        assertThat(result.intent()).isEqualTo(IntentCategory.TECHNICAL);
        assertThat(result.secondaryIntents()).doesNotContain(IntentCategory.BILLING, IntentCategory.GREETING);
    }

    /**
     * 多意图：跨域消息的两个领域都要留下痕迹。
     * 旧实现只回传单个 intent，AgentOrchestrator 只能另起一套硬编码关键词表来补，
     * 那套表与这里的模式词表并非同一份规则。
     *
     * <p>本用例保持语义通道开启（与生产一致）：这条消息每个领域只各命中一个内容词
     * （billing 命中"退款"、technical 命中"崩溃"），达不到 pattern-min-hits，
     * 主意图由语义通道选出，次意图由模式层的全部命中项带出。
     */
    @Test
    void crossDomainMessageExposesSecondaryIntent() {
        IntentRecognizer recognizer = recognizer(new EchoMindProperties(), llm("""
                {"intent":"other","confidence":0.0,"reasoning":"uncertain"}
                """));

        IntentResult result = recognizer.recognize("我要退款，但是 app 一直崩溃", null, "u1", List.of());

        assertThat(result.intent()).isEqualTo(IntentCategory.TECHNICAL);
        assertThat(result.secondaryIntents()).contains(IntentCategory.BILLING);
    }

    /** domainIntents 必须把次意图与模式层命中的其他领域一并带出，供协作路由复用同一份词表。 */
    @Test
    void domainIntentsMergesPatternEvidence() {
        IntentRecognizer recognizer = recognizer(new EchoMindProperties(), llm("""
                {"intent":"other","confidence":0.0,"reasoning":"uncertain"}
                """));

        RecognitionOutcome outcome = recognizer.recognizeUncached("我要退款，但是 app 一直崩溃", List.of());

        assertThat(recognizer.domainIntents(outcome))
                .contains(IntentCategory.TECHNICAL, IntentCategory.BILLING);
        assertThat(recognizer.domainIntents(null)).isEmpty();
    }

    /**
     * 只命中语用词（"什么时候/怎么"）的领域不算协作证据。
     * 否则一个纯账单问题会因为句尾有"什么时候"而多跑一个技术 Agent，白白增加延迟和重复内容。
     */
    @Test
    void questionPhrasingAloneIsNotCollaborationEvidence() {
        IntentRecognizer recognizer = recognizer(new EchoMindProperties(), llm("""
                {"intent":"other","confidence":0.0,"reasoning":"uncertain"}
                """));

        RecognitionOutcome outcome = recognizer.recognizeUncached("退款什么时候能到账", List.of());

        assertThat(recognizer.domainIntents(outcome)).doesNotContain(IntentCategory.TECHNICAL);
    }

    /** 缓存命中时也要能回答"哪条通道定的、次意图是什么"，否则命中与未命中的可观测性不一致。 */
    @Test
    void cachedOutcomeKeepsChannelEvidence() {
        IntentRecognizer recognizer = recognizer(new EchoMindProperties(), llm("""
                {"intent":"billing","confidence":0.9,"reasoning":"charged twice"}
                """));
        List<Map<String, String>> history = noHistory();

        recognizer.recognizeDetailed("为什么扣了两次款", "u1", "c1", history);
        RecognitionOutcome cached = recognizer.recognizeDetailed("为什么扣了两次款", "u1", "c1", history);

        assertThat(cached.cacheHit()).isTrue();
        assertThat(cached.result().intent()).isEqualTo(IntentCategory.BILLING);
        assertThat(cached.llm().confidence()).isEqualTo(0.9);
        assertThat(cached.llmFailure()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private IntentRecognizer recognizer(EchoMindProperties properties, LlmGateway gateway) {
        ObjectMapper objectMapper = new ObjectMapper();
        IntentSampleStore sampleStore = new IntentSampleStore(properties, objectMapper, new DefaultResourceLoader());
        sampleStore.load();
        TextEmbeddingService embeddingService = TextEmbeddingService.hashOnly();
        IntentVectorIndex vectorIndex = new IntentVectorIndex(sampleStore, embeddingService, properties);
        return new IntentRecognizer(gateway, objectMapper, sampleStore, vectorIndex, new EntityExtractor(), properties);
    }

    private LlmGateway llm(String response) {
        return (systemPrompt, userPrompt, temperature, maxTokens) -> response;
    }

    private List<Map<String, String>> noHistory() {
        return List.of();
    }
}
