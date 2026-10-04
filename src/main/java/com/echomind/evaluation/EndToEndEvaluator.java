package com.echomind.evaluation;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.ExecutionContext;
import com.echomind.agent.ExecutionStatus;
import com.echomind.agent.ExecutionMetrics;
import com.echomind.agent.AnswerVerifier.VerificationStatus;
import com.echomind.api.ChatPipeline;
import com.echomind.api.dto.ChatRequest;
import com.echomind.api.dto.ChatResponse;
import com.echomind.api.dto.EvalRunRequest;
import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.echomind.intent.IntentRecognizer;
import com.echomind.intent.IntentResult;
import com.echomind.intent.RecognitionOutcome;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.knowledge.KnowledgeAssessor;
import com.echomind.knowledge.SearchResult;
import com.echomind.tool.KnowledgeToolManager;
import com.echomind.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.function.Supplier;

@Service
public class EndToEndEvaluator {

    private static final Logger log = LoggerFactory.getLogger(EndToEndEvaluator.class);
    private static final Set<String> LOWER_IS_BETTER = Set.of("judge_failure_rate", "out_of_domain_false_accept_rate",
            "in_domain_abstain_rate", "intent_latency_p95_ms", "retrieval_no_answer_false_accept_rate",
            "retrieval_pipeline_no_answer_false_accept_rate", "retrieval_failure_rate", "retrieval_pipeline_failure_rate",
            "retrieval_latency_p95_ms", "retrieval_pipeline_latency_p95_ms", "dialog_latency_p95_ms",
            "dialog_partial_rate", "dialog_degraded_rate", "dialog_verification_unavailable_rate",
            "dialog_exact_sentence_duplicate_rate");

    private final IntentRecognizer intentRecognizer;
    private final AgentOrchestrator orchestrator;
    private final LLMJudge judge;
    private final ObjectMapper objectMapper;
    private final EchoMindProperties properties;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeToolManager knowledgeToolManager;
    private final KnowledgeAssessor knowledgeAssessor;
    private final ChatPipeline chatPipeline;

    public EndToEndEvaluator(IntentRecognizer intentRecognizer, AgentOrchestrator orchestrator, LLMJudge judge, ObjectMapper objectMapper, EchoMindProperties properties, KnowledgeBaseService knowledgeBaseService) {
        this(intentRecognizer, orchestrator, judge, objectMapper, properties, knowledgeBaseService, null);
    }

    public EndToEndEvaluator(IntentRecognizer intentRecognizer, AgentOrchestrator orchestrator, LLMJudge judge,
                             ObjectMapper objectMapper, EchoMindProperties properties,
                             KnowledgeBaseService knowledgeBaseService, KnowledgeToolManager knowledgeToolManager) {
        this(intentRecognizer, orchestrator, judge, objectMapper, properties, knowledgeBaseService, knowledgeToolManager, null);
    }

    @Autowired
    public EndToEndEvaluator(IntentRecognizer intentRecognizer, AgentOrchestrator orchestrator, LLMJudge judge,
                             ObjectMapper objectMapper, EchoMindProperties properties,
                             KnowledgeBaseService knowledgeBaseService, KnowledgeToolManager knowledgeToolManager,
                             ChatPipeline chatPipeline) {
        this.intentRecognizer = intentRecognizer;
        this.orchestrator = orchestrator;
        this.judge = judge;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeToolManager = knowledgeToolManager;
        this.knowledgeAssessor = new KnowledgeAssessor(properties);
        this.chatPipeline = chatPipeline;
    }

    public Map<String, Object> run(EvalRunRequest request) {
        List<EvalRunRequest.IntentCase> intentCases = request != null && request.intentCases() != null
                ? request.intentCases()
                : defaultIntentCases();
        List<EvalRunRequest.DialogCase> dialogCases = request != null && request.dialogCases() != null
                ? request.dialogCases()
                : defaultDialogCases();
        List<EvalRunRequest.RetrievalCase> retrievalCases = request != null && request.retrievalCases() != null
                ? request.retrievalCases()
                : defaultRetrievalCases();
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> predictions = new ArrayList<>();
        List<String> groundTruth = new ArrayList<>();
        long intentCorrect = 0;
        long accepted = 0;
        long outOfDomain = 0;
        long falseAccepts = 0;
        long inDomainAbstentions = 0;
        long domainTp = 0, domainFp = 0, domainFn = 0;
        long domainCases = 0, domainCorrect = 0;
        List<Long> intentLatencies = new ArrayList<>();
        Map<String, Map<String, Long>> confusion = new LinkedHashMap<>();
        for (EvalRunRequest.IntentCase c : intentCases) {
            //评测绕过缓存，并使用用例的独立标注和可选对话历史。
            RecognitionOutcome outcome = intentRecognizer.recognizeUncached(c.message(), c.history());
            IntentResult result = outcome.result();
            String predicted = result.intent().name().toLowerCase(Locale.ROOT);
            predictions.add(predicted);
            String expected = c.expectedIntent().trim().toLowerCase(Locale.ROOT);
            groundTruth.add(expected);
            boolean primaryCorrect = predicted.equals(expected);
            if (primaryCorrect) {
                intentCorrect++;
            }
            if (!result.abstained()) accepted++;
            if (expected.equals("other")) {
                outOfDomain++;
                if (!predicted.equals("other")) falseAccepts++;
            } else if (result.abstained()) {
                inDomainAbstentions++;
            }
            intentLatencies.add(result.latencyMs());
            confusion.computeIfAbsent(expected, ignored -> new LinkedHashMap<>()).merge(predicted, 1L, Long::sum);
            Set<String> predictedDomains = intentRecognizer.domainIntents(outcome).stream()
                    .map(intent -> intent.name().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
            boolean domainsMatch = true;
            if (c.expectedDomains() != null) {
                Set<String> expectedDomains = c.expectedDomains().stream().map(label -> label.trim().toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet());
                domainCases++;
                domainsMatch = predictedDomains.equals(expectedDomains);
                if (domainsMatch) domainCorrect++;
                domainTp += predictedDomains.stream().filter(expectedDomains::contains).count();
                domainFp += predictedDomains.stream().filter(label -> !expectedDomains.contains(label)).count();
                domainFn += expectedDomains.stream().filter(label -> !predictedDomains.contains(label)).count();
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("message", c.message());
            metadata.put("expected", expected);
            metadata.put("predicted", predicted);
            metadata.put("source", result.source());
            metadata.put("abstained", result.abstained());
            metadata.put("semantic_top1", outcome.semantic().best().name().toLowerCase(Locale.ROOT));
            metadata.put("semantic_score", round(outcome.semantic().score()));
            metadata.put("semantic_margin", round(outcome.semantic().margin()));
            metadata.put("llm_failure", outcome.llmFailure());
            metadata.put("embedding_space", outcome.semantic().embeddingSpace());
            metadata.put("predicted_domains", predictedDomains.stream().sorted().toList());
            metadata.put("expected_domains", c.expectedDomains() == null ? List.of() : c.expectedDomains());
            metadata.put("domains_evaluated", c.expectedDomains() != null);
            metadata.put("latency_ms", result.latencyMs());
            results.add(Map.of(
                    "test_id", "intent_" + results.size(),
                    "passed", primaryCorrect && domainsMatch,
                    "scores", Map.of(
                            "accuracy", primaryCorrect ? 1.0 : 0.0,
                            //置信度与定夺通道一并落库，便于区分"高置信度判错"和"证据不足弃权"
                            "confidence", round(result.confidence())
                    ),
                    "metadata", metadata
            ));
        }
        Map<String, RetrievalAccumulator> retrievalMetrics = evaluateRetrieval(retrievalCases, results);
        DialogAccumulator dialogMetrics = new DialogAccumulator();
        DialogAccumulator singleMetrics = new DialogAccumulator();
        boolean compareSingle = chatPipeline != null && request != null && Boolean.TRUE.equals(request.compareSingleAgent());
        List<Map<String, Object>> singleResults = new ArrayList<>();
        for (EvalRunRequest.DialogCase c : dialogCases) {
            List<String> turns = c.turns() != null && !c.turns().isEmpty() ? c.turns() : List.of(c.question());
            List<Map<String, String>> history = new ArrayList<>();
            List<Map<String, String>> singleHistory = new ArrayList<>();
            // Even supplied IDs are scoped to this run. Production memory is never read or written.
            String convId = "eval_" + UUID.randomUUID() + "_" + safe(c.conversationId());
            String userId = "eval_" + UUID.randomUUID() + "_" + safe(c.userId());
            for (String turn : turns) {
                ChatResponse response = dialogTurn(turn, userId, convId, history, true);
                QualityScores scores = judge.judge(turn, response.response(), judgeContext(response, history));
                dialogMetrics.record(c, response, scores);
                results.add(dialogResult("dialog_" + results.size(), c, turn, response, scores, "collaboration"));
                history.add(Map.of("role", "user", "content", turn));
                history.add(Map.of("role", "assistant", "content", response.response()));
                if (compareSingle) {
                    ChatResponse single = dialogTurn(turn, userId, convId + "_single", singleHistory, false);
                    QualityScores singleScores = judge.judge(turn, single.response(), judgeContext(single, singleHistory));
                    singleMetrics.record(c, single, singleScores);
                    singleResults.add(dialogResult("single_" + singleResults.size(), c, turn, single, singleScores, "single_agent"));
                    singleHistory.add(Map.of("role", "user", "content", turn));
                    singleHistory.add(Map.of("role", "assistant", "content", single.response()));
                }
            }
        }
        long passed = results.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();
        double intentAccuracy = intentCases.isEmpty() ? 0.0 : (double) intentCorrect / intentCases.size();
        Map<String, Object> avgScores = new HashMap<>();
        avgScores.put("intent_accuracy", round(intentAccuracy));
        avgScores.put("macro_f1", round(macroF1(predictions, groundTruth)));
        avgScores.put("intent_coverage", ratio(accepted, intentCases.size()));
        avgScores.put("out_of_domain_false_accept_rate", ratio(falseAccepts, outOfDomain));
        avgScores.put("in_domain_abstain_rate", ratio(inDomainAbstentions, intentCases.size() - outOfDomain));
        avgScores.put("multi_intent_precision", ratio(domainTp, domainTp + domainFp));
        avgScores.put("multi_intent_recall", ratio(domainTp, domainTp + domainFn));
        avgScores.put("multi_intent_exact_match", ratio(domainCorrect, domainCases));
        avgScores.put("intent_latency_p95_ms", percentile95(intentLatencies));
        retrievalMetrics.forEach((mode, metrics) -> {
            String prefix = mode.equals("base") ? "retrieval_" : "retrieval_pipeline_";
            avgScores.put(prefix + "recall_at_k", metrics.annotatedCases == 0 ? null : round(metrics.recallSum / metrics.annotatedCases));
            avgScores.put(prefix + "ndcg_at_k", metrics.annotatedCases == 0 ? null : round(metrics.ndcgSum / metrics.annotatedCases));
            avgScores.put(prefix + "mrr", ratioValue(metrics.reciprocalRankSum, metrics.answerableCases));
            avgScores.put(prefix + "hit_rate", ratio(metrics.hits, metrics.answerableCases));
            avgScores.put(prefix + "no_answer_false_accept_rate", ratio(metrics.falseAccepts, metrics.noAnswerCases));
            avgScores.put(prefix + "failure_rate", ratio(metrics.failures, metrics.cases));
            avgScores.put(prefix + "latency_p95_ms", percentile95(metrics.latencies));
        });
        avgScores.put("dialog_overall", round(avgDialogOverall(results)));
        avgScores.put("judge_failure_rate", round(judgeFailureRate(results)));
        dialogMetrics.summary().forEach((key, value) -> {
            if (value instanceof Number) avgScores.put("dialog_" + key, value);
        });
        List<String> regressions = detectRegressions(avgScores);
        Map<String, Object> report = Map.ofEntries(
                Map.entry("pass_rate", results.isEmpty() ? 0.0 : round((double) passed / results.size())),
                Map.entry("total", results.size()),
                Map.entry("passed", passed),
                Map.entry("avg_scores", avgScores),
                Map.entry("per_class", perClassMetrics(predictions, groundTruth)),
                Map.entry("confusion_matrix", confusion),
                Map.entry("multi_intent_cases", domainCases),
                Map.entry("out_of_domain_cases", outOfDomain),
                Map.entry("retrieval_metrics_version", 2),
                Map.entry("retrieval_pipeline_available", knowledgeToolManager != null),
                Map.entry("dialog_pipeline", chatPipeline == null ? "legacy_orchestrator_only" : "production_chat_pipeline_isolated"),
                Map.entry("dialog_metrics", dialogMetrics.summary()),
                Map.entry("single_agent_comparison", compareSingle ? Map.of("collaboration", dialogMetrics.summary(),
                        "single_agent", singleMetrics.summary(), "single_agent_results", singleResults,
                        "judge_calls_excluded_from_request_cost", true) : Map.of("enabled", false)),
                Map.entry("retrieval_modes", retrievalMetrics.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().summary(), (first, ignored) -> first, LinkedHashMap::new))),
                Map.entry("regressions", regressions),
                Map.entry("recommendations", recommendations(intentAccuracy, regressions)),
                Map.entry("results", results)
        );
        saveBaseline(report, request != null && Boolean.TRUE.equals(request.updateBaseline()));
        return report;
    }

    /**
     * 意图评测用例。
     *
     * <p>旧版本只有 8 条，且相当一部分与 intent-samples.json <b>逐字相同</b>——那些句子在语义
     * 通道会直接得到相似度 1.000，评测实际在测缓存与字符串相等，给出的准确率虚高，也无法反映改动好坏。
     * 当前 58 条复述句、边界句与域外句，由回归测试检查与样本库不存在归一化后的重复。
     */
    private List<EvalRunRequest.IntentCase> defaultIntentCases() {
        return List.of(
                // ---- query：只想知道信息，不含执行动作的诉求
                new EvalRunRequest.IntentCase("我的订单什么时候到？", "query"),
                new EvalRunRequest.IntentCase("退的钱几天能回来", "query"),
                new EvalRunRequest.IntentCase("你们周末上班吗", "query"),
                new EvalRunRequest.IntentCase("这个月账单怎么比上个月多了", "billing"),
                new EvalRunRequest.IntentCase("运费是怎么算的", "query"),
                new EvalRunRequest.IntentCase("在哪里能看我的历史订单", "query"),
                new EvalRunRequest.IntentCase("会员到期之后会自动扣费吗", "billing"),
                new EvalRunRequest.IntentCase("现在下单大概几天能收到", "query"),

                // ---- request：明确要求执行动作
                new EvalRunRequest.IntentCase("帮我取消刚下的订单", "request"),
                new EvalRunRequest.IntentCase("麻烦把收货地址改成公司", "request"),
                new EvalRunRequest.IntentCase("请帮我把发票重开一下", "request"),
                new EvalRunRequest.IntentCase("把这个订阅给我停掉", "request"),
                new EvalRunRequest.IntentCase("麻烦催一下订单发货进度", "request"),
                new EvalRunRequest.IntentCase("please help me change the delivery address", "request"),

                // ---- complaint：对已发生的体验表达不满，未要求转人工
                new EvalRunRequest.IntentCase("你们服务太差了！", "complaint"),
                new EvalRunRequest.IntentCase("等了三天了都没人理我", "complaint"),
                new EvalRunRequest.IntentCase("已经反映了两次还是没解决", "complaint"),
                new EvalRunRequest.IntentCase("说好昨天到货结果连发货都没有", "complaint"),
                new EvalRunRequest.IntentCase("每次都让我重新描述一遍，太折腾了", "complaint"),

                // ---- escalation：明确要求转人工/找负责人/声明投诉
                new EvalRunRequest.IntentCase("我要投诉，转人工！", "escalation"),
                new EvalRunRequest.IntentCase("给我接你们主管", "escalation"),
                new EvalRunRequest.IntentCase("别再让机器人回复我了，找真人", "escalation"),
                new EvalRunRequest.IntentCase("我要投诉到消费者协会", "escalation"),

                // ---- technical：产品故障与登录故障
                new EvalRunRequest.IntentCase("应用一直报500错误", "technical"),
                new EvalRunRequest.IntentCase("提交订单的时候页面卡住不动了", "technical"),
                new EvalRunRequest.IntentCase("收不到短信验证码", "technical"),
                new EvalRunRequest.IntentCase("app 一打开就闪退", "technical"),
                new EvalRunRequest.IntentCase("账号登录不上，提示密码错误", "technical"),
                new EvalRunRequest.IntentCase("上传的图片总是失败", "technical"),
                new EvalRunRequest.IntentCase("i keep getting a 500 error", "technical"),

                // ---- billing：扣款/退款/账单/发票/支付
                new EvalRunRequest.IntentCase("同一笔交易为什么扣了两次款", "billing"),
                new EvalRunRequest.IntentCase("我没有下单但是被扣钱了", "billing"),
                new EvalRunRequest.IntentCase("这笔扣款我根本不认识", "billing"),
                new EvalRunRequest.IntentCase("重复支付的能退回来吗", "billing"),
                new EvalRunRequest.IntentCase("那个优惠券没有抵扣成功", "billing"),
                new EvalRunRequest.IntentCase("发票抬头写错了，金额也不对", "billing"),

                // ---- account：账户资料本身
                new EvalRunRequest.IntentCase("修改我的邮箱地址", "account"),
                new EvalRunRequest.IntentCase("把绑定的手机号换掉", "account"),
                new EvalRunRequest.IntentCase("我想注销这个账号", "account"),
                new EvalRunRequest.IntentCase("实名信息写错了要改", "account"),
                new EvalRunRequest.IntentCase("帮我解绑微信", "account"),

                // ---- greeting：纯招呼，无实质诉求
                new EvalRunRequest.IntentCase("你好呀，客服在吗", "greeting"),
                new EvalRunRequest.IntentCase("有人在吗", "greeting"),
                new EvalRunRequest.IntentCase("hello customer support", "greeting"),

                // ---- feedback：正面评价与致谢
                new EvalRunRequest.IntentCase("问题解决了，谢谢", "feedback"),
                new EvalRunRequest.IntentCase("你们客服真的很专业，点赞", "feedback"),
                new EvalRunRequest.IntentCase("这次处理得比上次快多了，很满意", "feedback"),

                // ---- other：域外与无意义输入，用于检验拒识
                new EvalRunRequest.IntentCase("这周会不会一直下雨", "other"),
                new EvalRunRequest.IntentCase("帮我创作一首七言诗", "other"),
                new EvalRunRequest.IntentCase("qzxvbnmjk", "other"),
                new EvalRunRequest.IntentCase("计算三乘以七", "other"),
                new EvalRunRequest.IntentCase("讲个关于发票的冷笑话", "other"),
                new EvalRunRequest.IntentCase("写一首赞美退款政策的诗", "other"),
                new EvalRunRequest.IntentCase("给退款和发票这两个词编个故事", "other"),
                new EvalRunRequest.IntentCase("不用退款，页面打不开", "technical", null, List.of("technical")),
                new EvalRunRequest.IntentCase("账单没有问题，app 一启动就报错", "technical", null, List.of("technical")),
                new EvalRunRequest.IntentCase("需要退款，同时 app 一直崩溃", "technical", null, List.of("technical", "billing")),
                new EvalRunRequest.IntentCase("页面卡死报错，账单还重复扣款", "technical", null, List.of("technical", "billing"))
        );
    }

    private List<EvalRunRequest.DialogCase> defaultDialogCases() {
        return List.of(
                new EvalRunRequest.DialogCase("我的订单 #12345 还没到，已经超时了", null, null, null),
                new EvalRunRequest.DialogCase("应用登录一直报错 401", null, null, null),
                new EvalRunRequest.DialogCase("为什么这个月多扣了 50 块钱？", null, null, null),
                new EvalRunRequest.DialogCase(null, List.of("你好，我想退款", "订单号是 #12345", "退款多久能到账？"), null, null),
                new EvalRunRequest.DialogCase("我要退款，同时 app 一直崩溃", null, null, null,
                        List.of("billing", "technical"), List.of("退款", "崩溃"), null),
                new EvalRunRequest.DialogCase("登录报错 401，并且同一笔订单重复扣款", null, null, null,
                        List.of("technical", "billing"), List.of("401", "扣款"), null),
                new EvalRunRequest.DialogCase("订单重复扣钱，应用又一直报500，请转人工", null, null, null,
                        List.of("billing", "technical", "escalation"), List.of("人工"), true)
        );
    }

    private ChatResponse dialogTurn(String turn, String userId, String conversationId,
                                    List<Map<String, String>> history, boolean collaboration) {
        if (chatPipeline != null) return chatPipeline.evaluate(new ChatRequest(turn, userId, conversationId), history, collaboration);
        try (ExecutionContext execution = ExecutionContext.standalone(properties.getAgent().getRequestTimeoutMs())
                .withEvaluation(true).withCollaboration(collaboration)) {
            var result = orchestrator.run(AgentRequest.of(turn, userId, conversationId, history.toString(), history)
                    .withExecutionContext(execution));
            return new ChatResponse(conversationId, result.response(), result.intent() == null ? "other" : result.intent().name().toLowerCase(Locale.ROOT),
                    result.intentConfidence(), result.intentSource(), result.entities(), result.intentAbstained(),
                    result.agentType().name().toLowerCase(Locale.ROOT), result.escalated(), result.latencyMs(), false, List.of(),
                    false, "legacy evaluator does not run retrieval or verification", false, false, "verification unavailable in legacy constructor",
                    !result.toolCalls().isEmpty(), result.toolCalls(), List.of(), result.toolCalls().stream().mapToLong(call -> call.latencyMs()).sum(),
                    result.requestId(), result.status(), result.branches().stream().map(branch -> branch.actualAgent())
                            .filter(java.util.Objects::nonNull).map(agent -> agent.name().toLowerCase(Locale.ROOT)).distinct().toList(),
                    result.branches(), result.evidence(), VerificationStatus.SKIPPED,
                    result.metrics() == null ? execution.metrics() : result.metrics(),
                    result.secondaryIntents().stream().map(intent -> intent.name().toLowerCase(Locale.ROOT)).toList());
        }
    }

    private String judgeContext(ChatResponse response, List<Map<String, String>> history) {
        String evidence = response.evidence().stream().map(item -> "id=" + item.id() + " source=" + item.source()
                + "\n" + item.content()).reduce((left, right) -> left + "\n" + right).orElse("");
        String context = "[Evidence]\n" + evidence + "\n[Conversation]\n" + history;
        int max = Math.max(1, properties.getAgent().getContextMaxChars());
        return context.length() > max ? context.substring(0, max) : context;
    }

    private Map<String, Object> dialogResult(String id, EvalRunRequest.DialogCase c, String turn,
                                            ChatResponse response, QualityScores scores, String mode) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("question", turn);
        metadata.put("response", response.response());
        metadata.put("agent_type", response.agentType());
        metadata.put("participants", response.participants());
        metadata.put("expected_participants", c.expectedParticipants() == null ? List.of() : c.expectedParticipants());
        metadata.put("required_answer_terms", c.requiredAnswerTerms() == null ? List.of() : c.requiredAnswerTerms());
        metadata.put("execution_status", response.status().name().toLowerCase(Locale.ROOT));
        metadata.put("verification_status", response.verificationStatus().name().toLowerCase(Locale.ROOT));
        metadata.put("latency_ms", response.latencyMs());
        metadata.put("metrics", response.metrics());
        metadata.put("branches", response.branches());
        metadata.put("judge_failed", scores.judgeFailed());
        metadata.put("judge_error", scores.error() == null ? "" : scores.error());
        metadata.put("mode", mode);
        boolean labelsPass = termsCovered(c, response) == labels(c.requiredAnswerTerms()).size()
                && response.participants().containsAll(labels(c.expectedParticipants()))
                && (c.expectedEscalation() == null || c.expectedEscalation() == response.escalated());
        return Map.of("test_id", id, "passed", !scores.judgeFailed() && scores.overall() >= 0.75 && labelsPass
                        && response.status().hasAnswer() && (chatPipeline == null || response.verified()),
                "scores", Map.of("overall", round(scores.overall()), "relevance", scores.relevance(), "accuracy", scores.accuracy(),
                        "completeness", scores.completeness(), "helpfulness", scores.helpfulness()), "metadata", metadata);
    }

    private long termsCovered(EvalRunRequest.DialogCase c, ChatResponse response) {
        String answer = response.response().toLowerCase(Locale.ROOT);
        return labels(c.requiredAnswerTerms()).stream().filter(term -> answer.contains(term.toLowerCase(Locale.ROOT))).count();
    }

    /** Coverage uses independently supplied labels; duplicate rate is exact sentence repetition only. */
    private class DialogAccumulator {
        private long turns, partial, degraded, timeouts, verified, unavailable, skipped, qualityCases, escalationCases,
                escalationCorrect, participantTotal, participantCovered, termTotal, termCovered, sentences, duplicates,
                llmCalls, toolExecutions, sharedToolHits, inputTokens, outputTokens;
        private boolean tokenUsageAvailable = true;
        private double qualitySum;
        private final List<Long> latencies = new ArrayList<>();

        void record(EvalRunRequest.DialogCase c, ChatResponse response, QualityScores scores) {
            turns++;
            if (response.status() == ExecutionStatus.PARTIAL) partial++;
            if (response.status() == ExecutionStatus.DEGRADED) degraded++;
            if (response.status() == ExecutionStatus.TIMEOUT) timeouts++;
            if (response.verificationStatus() == VerificationStatus.PASS) verified++;
            if (response.verificationStatus() == VerificationStatus.UNAVAILABLE) unavailable++;
            if (response.verificationStatus() == VerificationStatus.SKIPPED) skipped++;
            if (!scores.judgeFailed()) { qualityCases++; qualitySum += scores.overall(); }
            if (c.expectedEscalation() != null) {
                escalationCases++;
                if (c.expectedEscalation() == response.escalated()) escalationCorrect++;
            }
            Set<String> expected = labels(c.expectedParticipants());
            participantTotal += expected.size();
            participantCovered += expected.stream().filter(response.participants()::contains).count();
            termTotal += labels(c.requiredAnswerTerms()).size();
            termCovered += termsCovered(c, response);
            Set<String> seen = new HashSet<>();
            for (String sentence : response.response().split("[。！？!?;；\\n]+")) {
                String normalized = sentence.replaceAll("[\\p{P}\\s]+", "").toLowerCase(Locale.ROOT);
                if (normalized.length() < 6) continue;
                sentences++;
                if (!seen.add(normalized)) duplicates++;
            }
            latencies.add(response.latencyMs());
            ExecutionMetrics metrics = response.metrics();
            llmCalls += metrics.llmCalls();
            toolExecutions += metrics.toolExecutions();
            sharedToolHits += metrics.sharedToolHits();
            if (metrics.inputTokens() < 0 || metrics.outputTokens() < 0) tokenUsageAvailable = false;
            else { inputTokens += metrics.inputTokens(); outputTokens += metrics.outputTokens(); }
        }

        Map<String, Object> summary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("turns", turns);
            summary.put("quality_overall", qualityCases == 0 ? null : round(qualitySum / qualityCases));
            summary.put("partial_rate", ratio(partial, turns));
            summary.put("degraded_rate", ratio(degraded, turns));
            summary.put("timeout_rate", ratio(timeouts, turns));
            summary.put("verification_pass_rate", ratio(verified, turns));
            summary.put("verification_unavailable_rate", ratio(unavailable, turns));
            summary.put("verification_skipped_rate", ratio(skipped, turns));
            summary.put("participant_label_count", participantTotal);
            summary.put("participant_recall", participantTotal == 0 ? null : ratio(participantCovered, participantTotal));
            summary.put("required_term_count", termTotal);
            summary.put("required_terms_coverage", termTotal == 0 ? null : ratio(termCovered, termTotal));
            summary.put("escalation_accuracy", escalationCases == 0 ? null : ratio(escalationCorrect, escalationCases));
            summary.put("exact_sentence_duplicate_rate", ratio(duplicates, sentences));
            summary.put("latency_p95_ms", percentile95(latencies));
            summary.put("llm_calls", llmCalls);
            summary.put("llm_calls_per_turn", ratio(llmCalls, turns));
            summary.put("llm_counter_scope", "chat_llm_excluding_background_profile_embeddings_and_eval_judge");
            summary.put("tool_executions", toolExecutions);
            summary.put("shared_tool_hits", sharedToolHits);
            boolean known = turns > 0 && tokenUsageAvailable;
            summary.put("token_usage_available", known);
            summary.put("input_tokens", known ? inputTokens : null);
            summary.put("output_tokens", known ? outputTokens : null);
            summary.put("monetary_cost_available", false);
            summary.put("monetary_cost", null);
            return summary;
        }
    }

    private List<EvalRunRequest.RetrievalCase> defaultRetrievalCases() {
        return List.of(
                documentCase("退款审核后多久能回到原支付账户", "退款政策"),
                documentCase("登录失败 401 是什么问题", "技术故障排查"),
                documentCase("满多少免运费", "配送说明"),
                documentCase("退款到账需要几天，以及标准配送几天送达？", "退款政策", "配送说明"),
                new EvalRunRequest.RetrievalCase("公司的火星基地地址和太空飞船燃料配方是什么", "", "", "", 4,
                        List.of(), List.of(), true, null)
        );
    }

    private EvalRunRequest.RetrievalCase documentCase(String query, String... titles) {
        return new EvalRunRequest.RetrievalCase(query, "", "", "", 4, null,
                java.util.Arrays.stream(titles).map(title -> KnowledgeBaseService.documentId("echomind-java", title)).toList(),
                false, null);
    }

    private Map<String, RetrievalAccumulator> evaluateRetrieval(List<EvalRunRequest.RetrievalCase> retrievalCases,
                                                               List<Map<String, Object>> results) {
        Map<String, RetrievalAccumulator> metrics = new LinkedHashMap<>();
        metrics.put("base", new RetrievalAccumulator());
        if (knowledgeToolManager != null) metrics.put("pipeline", new RetrievalAccumulator());
        if (retrievalCases == null || retrievalCases.isEmpty()) {
            return metrics;
        }
        for (EvalRunRequest.RetrievalCase c : retrievalCases) {
            validateRelevanceLabels(c);
            int topK = c.topK() == null ? properties.getRag().getTopK() : c.topK();
            if (topK < 1 || topK > 50) throw new IllegalArgumentException("评测 topK 必须在 1 到 50 之间");
            for (Map.Entry<String, RetrievalAccumulator> mode : metrics.entrySet()) {
                Map<String, Long> before = mode.getKey().equals("pipeline") ? pipelineCounters() : Map.of();
                RetrievalObservation observation = observe(() -> {
                    if (mode.getKey().equals("pipeline")) {
                        ToolResult<List<SearchResult>> result = knowledgeToolManager.searchUncached(safe(c.query()), topK, c.history());
                        return new RetrievalObservation(result.success(), result.data(), result.degraded(), result.cached(),
                                result.reranked(), safe(result.error()), "production_pipeline", 0);
                    }
                    if (knowledgeBaseService == null) throw new IllegalStateException("知识库检索不可用");
                    var batch = knowledgeBaseService.searchDetailed(safe(c.query()), topK);
                    boolean success = !batch.degraded() || !uniqueResults(batch.results()).isEmpty();
                    return new RetrievalObservation(success, batch.results(), batch.degraded(), false, false,
                            success ? "" : "retrieval backend degraded with no usable results", batch.backendKey(), 0);
                });
                Map<String, Long> counters = counterDelta(before, mode.getKey().equals("pipeline") ? pipelineCounters() : Map.of());
                List<SearchResult> candidates = observation.success()
                        ? uniqueResults(observation.results()).stream().limit(topK).toList() : List.of();
                RetrievalScore scores = scoreRetrieval(c, candidates, topK);
                boolean sufficient = observation.success() && knowledgeAssessor.knowledgeSufficient(knowledgeAssessor.usefulKnowledge(candidates));
                boolean noAnswer = Boolean.TRUE.equals(c.noAnswer());
                boolean passed = observation.success() && (noAnswer ? !sufficient : scores.annotated()
                        ? scores.recall() == 1.0 : scores.rank() > 0);
                mode.getValue().record(scores, observation, noAnswer, sufficient, counters);

                Map<String, Object> perCaseScores = new LinkedHashMap<>();
                perCaseScores.put("recall_at_k", scores.annotated() && !noAnswer ? round(scores.recall()) : null);
                perCaseScores.put("ndcg_at_k", scores.annotated() && !noAnswer ? round(scores.ndcg()) : null);
                perCaseScores.put("hit_at_k", scores.rank() > 0 ? 1.0 : 0.0);
                perCaseScores.put("reciprocal_rank", scores.rank() > 0 ? round(1.0 / scores.rank()) : 0.0);
                perCaseScores.put("top_score", candidates.isEmpty() ? 0.0 : candidates.getFirst().score());
                perCaseScores.put("no_answer_false_accept", noAnswer && sufficient ? 1.0 : 0.0);

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("mode", mode.getKey());
                metadata.put("query", safe(c.query()));
                metadata.put("expected_title", safe(c.expectedTitle()));
                metadata.put("expected_text", safe(c.expectedText()));
                metadata.put("expected_source", safe(c.expectedSource()));
                metadata.put("relevant_chunk_ids", labels(c.relevantChunkIds()).stream().toList());
                metadata.put("relevant_document_ids", labels(c.relevantDocumentIds()).stream().toList());
                metadata.put("relevance_annotation", scores.annotation());
                metadata.put("relevant_total", scores.relevantTotal());
                metadata.put("relevant_retrieved", scores.relevantRetrieved());
                metadata.put("no_answer", noAnswer);
                metadata.put("knowledge_sufficient", sufficient);
                metadata.put("success", observation.success());
                metadata.put("degraded", observation.degraded());
                metadata.put("cached", observation.cached());
                metadata.put("reranked", observation.reranked());
                metadata.put("backend", observation.backend());
                metadata.put("error", observation.error());
                metadata.put("latency_ms", observation.latencyMs());
                metadata.put("top_k", topK);
                metadata.put("rank", scores.rank());
                metadata.put("pipeline_counter_delta", counters);
                metadata.put("counter_scope", counters.isEmpty() ? "unavailable" : "shared_manager; concurrent requests may contribute");
                metadata.put("top_results", candidates.stream().map(this::resultMetadata).toList());
                results.add(Map.of("test_id", "retrieval_" + mode.getKey() + "_" + results.size(),
                        "passed", passed, "scores", perCaseScores, "metadata", metadata));
            }
        }
        return metrics;
    }

    private RetrievalObservation observe(Supplier<RetrievalObservation> action) {
        long start = System.nanoTime();
        RetrievalObservation observation;
        try {
            observation = action.get();
        } catch (Exception ex) {
            observation = new RetrievalObservation(false, List.of(), false, false, false,
                    safe(ex.getMessage()), "unavailable", 0);
        }
        return new RetrievalObservation(observation.success(), observation.results(), observation.degraded(),
                observation.cached(), observation.reranked(), observation.error(), observation.backend(),
                (System.nanoTime() - start) / 1_000_000);
    }

    private List<SearchResult> uniqueResults(List<SearchResult> results) {
        Map<String, SearchResult> unique = new LinkedHashMap<>();
        if (results != null) results.stream().filter(result -> result != null && !knowledgeAssessor.isFallbackKnowledge(result))
                .forEach(result -> unique.putIfAbsent(result.id(), result));
        return new ArrayList<>(unique.values());
    }

    private RetrievalScore scoreRetrieval(EvalRunRequest.RetrievalCase c, List<SearchResult> results, int topK) {
        Set<String> chunks = labels(c.relevantChunkIds());
        Set<String> documents = labels(c.relevantDocumentIds());
        Set<String> relevant = chunks.isEmpty() ? documents : chunks;
        boolean annotated = !relevant.isEmpty();
        String annotation = !chunks.isEmpty() ? "chunk_ids" : !documents.isEmpty() ? "document_ids"
                : Boolean.TRUE.equals(c.noAnswer()) ? "no_answer" : "legacy_keyword_hit_only";
        Set<String> found = new HashSet<>();
        int firstRank = 0;
        double dcg = 0.0;
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            String key = chunks.isEmpty() ? documentId(result) : result.id();
            boolean match = annotated ? relevant.contains(key) : !Boolean.TRUE.equals(c.noAnswer()) && matches(c, result);
            if (match) {
                if (firstRank == 0) firstRank = i + 1;
                // 多个 chunk 命中同一相关文档时，只有第一次贡献 recall 和 DCG。
                if (found.add(key)) dcg += 1.0 / log2(i + 2);
            }
        }
        double ideal = 0.0;
        for (int i = 0; i < Math.min(topK, relevant.size()); i++) ideal += 1.0 / log2(i + 2);
        return new RetrievalScore(annotated, annotation, relevant.size(), found.size(), firstRank,
                annotated ? (double) found.size() / relevant.size() : 0.0, ideal == 0.0 ? 0.0 : dcg / ideal);
    }

    private void validateRelevanceLabels(EvalRunRequest.RetrievalCase c) {
        if (c == null || c.query() == null || c.query().isBlank())
            throw new IllegalArgumentException("评测检索 query 不能为空");
        if (!labels(c.relevantChunkIds()).isEmpty() && !labels(c.relevantDocumentIds()).isEmpty())
            throw new IllegalArgumentException("相关 chunk 和文档 ID 集合请只提供一种，以保持 Recall 分母明确");
        if (Boolean.TRUE.equals(c.noAnswer()) && (!labels(c.relevantChunkIds()).isEmpty() || !labels(c.relevantDocumentIds()).isEmpty()))
            throw new IllegalArgumentException("无答案用例不能同时标注相关 ID");
    }

    private Set<String> labels(List<String> values) {
        if (values == null) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        values.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).forEach(result::add);
        return result;
    }

    private String documentId(SearchResult result) {
        Map<String, Object> metadata = result.metadata() == null ? Map.of() : result.metadata();
        return String.valueOf(metadata.getOrDefault("document_id", ""));
    }

    private Map<String, Object> resultMetadata(SearchResult result) {
        Map<String, Object> metadata = result.metadata() == null ? Map.of() : result.metadata();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", safe(result.id()));
        data.put("document_id", documentId(result));
        data.put("title", safe(result.title()));
        data.put("score", result.score());
        data.put("retrieval_score", result.retrievalScore());
        data.put("rerank_rank", result.rerankRank());
        data.put("source", String.valueOf(metadata.getOrDefault("source", "")));
        return data;
    }

    private Map<String, Long> pipelineCounters() {
        Map<String, Long> counters = new LinkedHashMap<>();
        Object search = knowledgeToolManager.stats().get("knowledge_search");
        if (search instanceof Map<?, ?> values) {
            for (String key : List.of("rewrite_calls", "rerank_calls", "llm_calls", "query_count",
                    "rewrite_latency_ms", "recall_latency_ms", "rerank_latency_ms")) {
                if (values.get(key) instanceof Number number) counters.put(key, number.longValue());
            }
        }
        return counters;
    }

    private Map<String, Long> counterDelta(Map<String, Long> before, Map<String, Long> after) {
        Map<String, Long> counters = new LinkedHashMap<>();
        before.forEach((key, value) -> {
            if (after.containsKey(key)) counters.put(key, Math.max(0, after.get(key) - value));
        });
        return counters;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2.0);
    }

    private double ratioValue(double numerator, long denominator) {
        return denominator == 0 ? 0.0 : round(numerator / denominator);
    }

    private boolean matches(EvalRunRequest.RetrievalCase c, SearchResult result) {
        Map<String, Object> metadata = result.metadata() == null ? Map.of() : result.metadata();
        boolean labelled = !safe(c.expectedTitle()).isBlank() || !safe(c.expectedText()).isBlank() || !safe(c.expectedSource()).isBlank();
        return labelled && containsIfPresent(result.title(), c.expectedTitle())
                && containsIfPresent(result.content(), c.expectedText())
                && containsIfPresent(String.valueOf(metadata.getOrDefault("source", "")), c.expectedSource());
    }

    private boolean containsIfPresent(String actual, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return actual != null && actual.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    private double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : round((double) numerator / denominator);
    }

    private long percentile95(List<Long> values) {
        if (values.isEmpty()) return 0;
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }

    private double macroF1(List<String> predictions, List<String> groundTruth) {
        Map<String, Map<String, Double>> perClass = perClassMetrics(predictions, groundTruth);
        return perClass.values().stream().mapToDouble(m -> m.getOrDefault("f1", 0.0)).average().orElse(0.0);
    }

    private Map<String, Map<String, Double>> perClassMetrics(List<String> predictions, List<String> groundTruth) {
        Set<String> labels = new HashSet<>();
        labels.addAll(predictions);
        labels.addAll(groundTruth);
        Map<String, Map<String, Double>> metrics = new HashMap<>();
        for (String label : labels) {
            int tp = 0;
            int fp = 0;
            int fn = 0;
            for (int i = 0; i < predictions.size(); i++) {
                boolean p = label.equals(predictions.get(i));
                boolean g = label.equals(groundTruth.get(i));
                if (p && g) tp++;
                if (p && !g) fp++;
                if (!p && g) fn++;
            }
            double precision = tp + fp == 0 ? 0.0 : (double) tp / (tp + fp);
            double recall = tp + fn == 0 ? 0.0 : (double) tp / (tp + fn);
            double f1 = precision + recall == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
            metrics.put(label, Map.of("precision", round(precision), "recall", round(recall), "f1", round(f1)));
        }
        return metrics;
    }

    @SuppressWarnings("unchecked")
    private double avgDialogOverall(List<Map<String, Object>> results) {
        //judge 失败的样本从均值中剔除：失败样本的 0.5 兜底分会在 LLM 整体
        //不可用时把 dialog_overall 稳定在 0.5，掩盖真实的质量塌方
        return results.stream()
                .filter(r -> String.valueOf(r.get("test_id")).startsWith("dialog_"))
                .filter(r -> !judgeFailed(r))
                .map(r -> (Map<String, Object>) r.get("scores"))
                .mapToDouble(scores -> ((Number) scores.getOrDefault("overall", 0.0)).doubleValue())
                .average()
                .orElse(0.0);
    }

    private double judgeFailureRate(List<Map<String, Object>> results) {
        List<Map<String, Object>> dialogs = results.stream()
                .filter(r -> String.valueOf(r.get("test_id")).startsWith("dialog_"))
                .toList();
        if (dialogs.isEmpty()) {
            return 0.0;
        }
        long failed = dialogs.stream().filter(this::judgeFailed).count();
        return (double) failed / dialogs.size();
    }

    private boolean judgeFailed(Map<String, Object> result) {
        Object metadata = result.get("metadata");
        return metadata instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("judge_failed"));
    }

    @SuppressWarnings("unchecked")
    private List<String> detectRegressions(Map<String, Object> currentScores) {
        Path path = Path.of(properties.getEval().getBaselinePath());
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            Map<String, Object> previous = objectMapper.readValue(path.toFile(), new TypeReference<>() {
            });
            Map<String, Object> prevScores = (Map<String, Object>) previous.getOrDefault("avg_scores", Map.of());
            List<String> regressions = new ArrayList<>();
            for (Map.Entry<String, Object> entry : currentScores.entrySet()) {
                // 旧版本将 Hit@K 叫作 Recall@K；保留旧基线中的意图/命中率指标，
                // 但不能将旧口径的“recall”与完整相关集合的新 Recall 比较。
                if (entry.getKey().equals("retrieval_recall_at_k")
                        && !Integer.valueOf(2).equals(previous.get("retrieval_metrics_version"))) continue;
                Object prev = prevScores.get(entry.getKey());
                if (prev instanceof Number p && entry.getValue() instanceof Number c) {
                    boolean worsened = LOWER_IS_BETTER.contains(entry.getKey())
                            ? c.doubleValue() > p.doubleValue() * 1.05
                            : p.doubleValue() > 0 && c.doubleValue() < p.doubleValue() * 0.95;
                    if (worsened) {
                        regressions.add(entry.getKey() + ": " + round(p.doubleValue()) + " -> " + round(c.doubleValue()));
                    }
                }
            }
            return regressions;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private synchronized void saveBaseline(Map<String, Object> report, boolean replaceExisting) {
        try {
            Path path = Path.of(properties.getEval().getBaselinePath());
            // 保留固定比较基准，避免每次运行把退化结果变成下一次的“正确基线”。
            if (!replaceExisting && Files.exists(path)) return;
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            //临时文件 + 原子替换：并发或中途崩溃不会留下残缺 JSON，
            //否则 detectRegressions 会静默失效
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), report);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ex) {
            log.error("Failed to save evaluation baseline", ex);
        }
    }

    private List<String> recommendations(double intentAccuracy, List<String> regressions) {
        List<String> recs = new ArrayList<>();
        if (intentAccuracy < 0.9) {
            recs.add("检查误分类样例与标签边界，使用独立训练样本改进后在保留测试集上重新评测");
        }
        if (!regressions.isEmpty()) {
            recs.add("发现评测回归，请对比 baseline 中退化指标并检查最近 prompt 或检索逻辑变更");
        }
        if (recs.isEmpty()) {
            recs.add("主意图准确率未触发检查阈值；请同时检查域外误接纳、多意图指标和用例覆盖范围");
        }
        return recs;
    }

    private record RetrievalObservation(boolean success, List<SearchResult> results, boolean degraded, boolean cached,
                                        boolean reranked, String error, String backend, long latencyMs) {
    }

    private record RetrievalScore(boolean annotated, String annotation, int relevantTotal, int relevantRetrieved,
                                  int rank, double recall, double ndcg) {
    }

    private class RetrievalAccumulator {
        private long cases, answerableCases, annotatedCases, noAnswerCases, hits, falseAccepts, failures, degraded, cacheHits;
        private double recallSum, reciprocalRankSum, ndcgSum;
        private final List<Long> latencies = new ArrayList<>();
        private final Map<String, Long> counters = new LinkedHashMap<>();

        void record(RetrievalScore score, RetrievalObservation observation, boolean noAnswer, boolean sufficient,
                    Map<String, Long> costCounters) {
            cases++;
            if (!observation.success()) failures++;
            if (observation.degraded()) degraded++;
            if (observation.cached()) cacheHits++;
            latencies.add(observation.latencyMs());
            costCounters.forEach((key, value) -> counters.merge(key, value, Long::sum));
            if (noAnswer) {
                noAnswerCases++;
                if (sufficient) falseAccepts++;
            } else {
                answerableCases++;
                if (score.rank() > 0) {
                    hits++;
                    reciprocalRankSum += 1.0 / score.rank();
                }
                if (score.annotated()) {
                    annotatedCases++;
                    recallSum += score.recall();
                    ndcgSum += score.ndcg();
                }
            }
        }

        Map<String, Object> summary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("cases", cases);
            summary.put("answerable_cases", answerableCases);
            summary.put("annotated_cases", annotatedCases);
            summary.put("legacy_keyword_cases", answerableCases - annotatedCases);
            summary.put("no_answer_cases", noAnswerCases);
            summary.put("recall_at_k", annotatedCases == 0 ? null : round(recallSum / annotatedCases));
            summary.put("ndcg_at_k", annotatedCases == 0 ? null : round(ndcgSum / annotatedCases));
            summary.put("mrr", ratioValue(reciprocalRankSum, answerableCases));
            summary.put("hit_rate", ratio(hits, answerableCases));
            summary.put("no_answer_false_accept_rate", ratio(falseAccepts, noAnswerCases));
            summary.put("failure_rate", ratio(failures, cases));
            summary.put("degraded_rate", ratio(degraded, cases));
            summary.put("cache_hit_rate", ratio(cacheHits, cases));
            summary.put("latency_p95_ms", percentile95(latencies));
            summary.put("latency_avg_ms", latencies.isEmpty() ? 0.0 : round(latencies.stream().mapToLong(Long::longValue).average().orElse(0.0)));
            summary.put("pipeline_counter_delta", counters);
            summary.put("counter_scope", counters.isEmpty() ? "unavailable" : "shared_manager; concurrent requests may contribute");
            summary.put("token_and_monetary_cost_available", false);
            return summary;
        }
    }
}
