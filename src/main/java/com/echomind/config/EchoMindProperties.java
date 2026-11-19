package com.echomind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "echomind")
public class EchoMindProperties {

    private final Llm llm = new Llm();
    private final Agent agent = new Agent();
    private final Memory memory = new Memory();
    private final Rag rag = new Rag();
    private final Storage storage = new Storage();
    private final Intent intent = new Intent();
    private final Monitor monitor = new Monitor();
    private final Eval eval = new Eval();
    private final Skills skills = new Skills();
    private final Mcp mcp = new Mcp();
    private final Security security = new Security();

    public Security getSecurity() {
        return security;
    }

    public Llm getLlm() {
        return llm;
    }

    public Agent getAgent() { return agent; }

    public static class Agent {
        private long requestTimeoutMs = 50000;
        private long branchTimeoutMs = 30000;
        private long verificationTimeoutMs = 8000;
        private int maxRepairAttempts = 1;
        private int contextMaxChars = 18000;
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 100;
        private int operationCorePoolSize = 8;
        private int operationMaxPoolSize = 32;
        private int operationQueueCapacity = 100;
        private Map<String, List<String>> toolAllowlist = new LinkedHashMap<>();

        public long getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(long value) { requestTimeoutMs = Math.max(1, value); }
        public long getBranchTimeoutMs() { return branchTimeoutMs; }
        public void setBranchTimeoutMs(long value) { branchTimeoutMs = Math.max(1, value); }
        public long getVerificationTimeoutMs() { return verificationTimeoutMs; }
        public void setVerificationTimeoutMs(long value) { verificationTimeoutMs = Math.max(1, value); }
        public int getMaxRepairAttempts() { return maxRepairAttempts; }
        public void setMaxRepairAttempts(int value) { maxRepairAttempts = Math.max(0, Math.min(1, value)); }
        public int getContextMaxChars() { return contextMaxChars; }
        public void setContextMaxChars(int value) { contextMaxChars = Math.max(1000, value); }
        public int getCorePoolSize() { return corePoolSize; }
        public void setCorePoolSize(int value) { corePoolSize = Math.max(1, value); }
        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int value) { maxPoolSize = Math.max(1, value); }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int value) { queueCapacity = Math.max(1, value); }
        public int getOperationCorePoolSize() { return operationCorePoolSize; }
        public void setOperationCorePoolSize(int value) { operationCorePoolSize = Math.max(1, value); }
        public int getOperationMaxPoolSize() { return operationMaxPoolSize; }
        public void setOperationMaxPoolSize(int value) { operationMaxPoolSize = Math.max(1, value); }
        public int getOperationQueueCapacity() { return operationQueueCapacity; }
        public void setOperationQueueCapacity(int value) { operationQueueCapacity = Math.max(1, value); }
        public Map<String, List<String>> getToolAllowlist() { return toolAllowlist; }
        public void setToolAllowlist(Map<String, List<String>> value) {
            toolAllowlist = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
        }
    }

    public Memory getMemory() {
        return memory;
    }

    public Rag getRag() {
        return rag;
    }

    public Storage getStorage() {
        return storage;
    }

    public Intent getIntent() {
        return intent;
    }

    public Monitor getMonitor() {
        return monitor;
    }

    public Eval getEval() {
        return eval;
    }

    public Skills getSkills() {
        return skills;
    }

    public Mcp getMcp() {
        return mcp;
    }

    public static class Llm {
        private boolean fallbackEnabled = true;

        public boolean isFallbackEnabled() {
            return fallbackEnabled;
        }

        public void setFallbackEnabled(boolean fallbackEnabled) {
            this.fallbackEnabled = fallbackEnabled;
        }
    }

    public static class Memory {
        private long ttlSeconds = 86400;
        private int workingMax = 20;
        private int compressAt = 15;

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public int getWorkingMax() {
            return workingMax;
        }

        public void setWorkingMax(int workingMax) {
            this.workingMax = workingMax;
        }

        public int getCompressAt() {
            return compressAt;
        }

        public void setCompressAt(int compressAt) {
            this.compressAt = compressAt;
        }
    }

    public static class Rag {
        private int topK = 4;
        private double bm25Weight = 0.45;
        private double vectorWeight = 0.55;
        private double minRelevantScore = 0.18;
        private int minSources = 1;
        private int contextWindow = 1;
        private String fusionStrategy = "rrf";
        private int rrfK = 60;
        private int candidateK = 20;
        private int rerankK = 20;
        private boolean rewriteEnabled = true;
        private int maxRewriteQueries = 3;
        private int rewriteMinQueryChars = 6;
        private double rewriteTriggerScore = 0.35;
        private long timeoutMs = 30000;
        private long rewriteTimeoutMs = 3000;
        private long rerankTimeoutMs = 5000;
        private int cacheMaxEntries = 2000;
        private long cacheTtlSeconds = 300;
        private int rerankMaxInputTokens = 4000;
        private int maxChunkTokens = 512;
        private int overlapTokens = 64;
        private int contextMaxTokens = 3000;
        private double hashMinRelevantScore = 0.18;

        public int getCandidateK() { return candidateK; }
        public void setCandidateK(int value) { candidateK = Math.max(1, Math.min(200, value)); }
        public int getRerankK() { return rerankK; }
        public void setRerankK(int value) { rerankK = Math.max(1, Math.min(200, value)); }
        public boolean isRewriteEnabled() { return rewriteEnabled; }
        public void setRewriteEnabled(boolean value) { rewriteEnabled = value; }
        public int getMaxRewriteQueries() { return maxRewriteQueries; }
        public void setMaxRewriteQueries(int value) { maxRewriteQueries = Math.max(0, Math.min(8, value)); }
        public int getRewriteMinQueryChars() { return rewriteMinQueryChars; }
        public void setRewriteMinQueryChars(int value) { rewriteMinQueryChars = Math.max(1, value); }
        public double getRewriteTriggerScore() { return rewriteTriggerScore; }
        public void setRewriteTriggerScore(double value) { rewriteTriggerScore = boundedScore(value); }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long value) { timeoutMs = Math.max(1, value); }
        public long getRewriteTimeoutMs() { return rewriteTimeoutMs; }
        public void setRewriteTimeoutMs(long value) { rewriteTimeoutMs = Math.max(1, value); }
        public long getRerankTimeoutMs() { return rerankTimeoutMs; }
        public void setRerankTimeoutMs(long value) { rerankTimeoutMs = Math.max(1, value); }
        public int getCacheMaxEntries() { return cacheMaxEntries; }
        public void setCacheMaxEntries(int value) { cacheMaxEntries = Math.max(1, value); }
        public long getCacheTtlSeconds() { return cacheTtlSeconds; }
        public void setCacheTtlSeconds(long value) { cacheTtlSeconds = Math.max(0, value); }
        public int getRerankMaxInputTokens() { return rerankMaxInputTokens; }
        public void setRerankMaxInputTokens(int value) { rerankMaxInputTokens = Math.max(128, value); }
        public int getMaxChunkTokens() { return maxChunkTokens; }
        public void setMaxChunkTokens(int value) { maxChunkTokens = Math.max(32, value); }
        public int getOverlapTokens() { return overlapTokens; }
        public void setOverlapTokens(int value) { overlapTokens = Math.max(0, value); }
        public int getContextMaxTokens() { return contextMaxTokens; }
        public void setContextMaxTokens(int value) { contextMaxTokens = Math.max(1, value); }
        public double getHashMinRelevantScore() { return hashMinRelevantScore; }
        public void setHashMinRelevantScore(double value) { hashMinRelevantScore = boundedScore(value); }

        private static double boundedScore(double value) {
            return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = Math.max(1, Math.min(50, topK));
        }

        public double getBm25Weight() {
            return bm25Weight;
        }

        public void setBm25Weight(double bm25Weight) {
            this.bm25Weight = bm25Weight;
        }

        public double getVectorWeight() {
            return vectorWeight;
        }

        public void setVectorWeight(double vectorWeight) {
            this.vectorWeight = vectorWeight;
        }

        public double getMinRelevantScore() {
            return minRelevantScore;
        }

        public void setMinRelevantScore(double minRelevantScore) {
            this.minRelevantScore = boundedScore(minRelevantScore);
        }

        public int getMinSources() {
            return minSources;
        }

        public void setMinSources(int minSources) {
            this.minSources = Math.max(1, minSources);
        }

        public int getContextWindow() {
            return contextWindow;
        }

        public void setContextWindow(int contextWindow) {
            this.contextWindow = Math.max(0, Math.min(10, contextWindow));
        }

        public String getFusionStrategy() {
            return fusionStrategy;
        }

        public void setFusionStrategy(String fusionStrategy) {
            this.fusionStrategy = fusionStrategy == null || fusionStrategy.isBlank() ? "weighted" : fusionStrategy;
        }

        public int getRrfK() {
            return rrfK;
        }

        public void setRrfK(int rrfK) {
            this.rrfK = Math.max(1, rrfK);
        }
    }

    public static class Storage {
        private String dataDir = "data/java";
        private String knowledgePath = "data/java/knowledge-store.json";
        private String memoryPath = "data/java/memory-store.json";

        public String getDataDir() {
            return dataDir;
        }

        public void setDataDir(String dataDir) {
            this.dataDir = dataDir;
        }

        public String getKnowledgePath() {
            return knowledgePath;
        }

        public void setKnowledgePath(String knowledgePath) {
            this.knowledgePath = knowledgePath;
        }

        public String getMemoryPath() {
            return memoryPath;
        }

        public void setMemoryPath(String memoryPath) {
            this.memoryPath = memoryPath;
        }
    }

    /**
     * 意图识别参数。旧实现只暴露 semanticThreshold，其余阈值（LLM 直采阈值 0.6、
     * 拒识阈值 0.5、语义 margin、缓存容量）全部硬编码在 IntentRecognizer 里，
     * 调优必须改代码重新编译；这里统一收敛为配置项。
     */
    public static class Intent {
        private String samplesPath = "classpath:intent-samples.json";
        /**
         * 是否启用语义（样本向量）通道。生产环境应保持开启；关闭后意图完全由 LLM 与模式通道决定，
         * 主要用于验证模式通道的独立行为，以及 embedding 后端异常时快速隔离故障。
         */
        private boolean semanticEnabled = true;
        /**
         * 语义通道独立定夺意图所需的最低相似度。必须按 embedding 后端分别标定：
         * hash 兜底向量与真实 embedding 的相似度分布完全不同（hash 空间下复述句普遍 < 0.40）。
         * 当前默认值是针对 hash 兜底向量实测标定的——接真实 embedding 时应重新标定，
         * 否则 0.42~0.55 这一档的"同句式不同意图"噪声会被当成强证据
         * （实测："会员到期之后会自动扣费吗" 与 query 样本的 hash 相似度达 0.452，属 billing 却被判成 query）。
         */
        private double semanticThreshold = 0.45;
        /** 语义通道独立定夺所需的 top1-top2 间距，防止"两个类分数几乎相同"时被随机挑一个。 */
        private double semanticMargin = 0.05;
        /**
         * 语义"弱证据"档位：分数未达 semantic-threshold、但与次优拉开 weak-margin 时，
         * 仍允许它补位（仅在 LLM 弃权或低置信时）。实测 hash 兜底向量下复述句绝对相似度
         * 普遍只有 0.15~0.45，只认绝对阈值会让兜底路径几乎全线弃权。
         */
        private double weakScore = 0.15;
        private double weakMargin = 0.08;
        /** 按标签保留的最近样本数；最高相似度用于决策，窗口内支持数用于平票裁决。 */
        private int semanticTopK = 3;
        /** LLM 自报置信度达到该值即直接采信，不再让其他通道覆盖。 */
        private double llmDirectThreshold = 0.6;
        /** 低于该置信度视为 LLM 弃权（abstain），交由语义/模式通道补位。 */
        private double minConfidence = 0.5;
        /** 命中关键词数量达到该值即允许模式通道独立定夺（模式通道不再与 LLM 做数值加权）。 */
        private int patternMinHits = 2;
        /** LLM 提示词和缓存键共同使用的历史消息条数（user/assistant 各算一条）。 */
        private int cacheHistoryTurns = 4;
        /** 识别结果缓存容量，按 LRU 淘汰；旧实现在超过 1000 条时整体 clear()，会连热点一起清掉。 */
        private int cacheMaxEntries = 2000;
        /** 识别缓存有效期；0 表示关闭缓存，故障降级结果不缓存。 */
        private int cacheTtlSeconds = 300;
        /**
         * 模式层关键词表。旧实现硬编码在 IntentRecognizer.patternRecognize 中，且 query 词表含裸 "?"/"？"，
         * 导致任何带问号的消息都白送 query 权重；中英混排词表还会按总长度稀释置信度。
         * 这里改为配置化，并去掉裸标点。
         */
        private Map<String, List<String>> patterns = defaultPatterns();

        private static Map<String, List<String>> defaultPatterns() {
            Map<String, List<String>> defaults = new LinkedHashMap<>();
            defaults.put("escalation", List.of("投诉", "经理", "转人工", "人工客服", "主管", "负责人", "上级",
                    "真人", "12315", "消费者协会", "法律途径", "supervisor"));
            defaults.put("complaint", List.of("太差", "糟糕", "差评", "很差", "太慢", "等了很久", "等了好几",
                    "没人理", "没人管", "没人处理", "没人回复", "没解决", "没兑现", "没发货", "都没有",
                    "反映了两", "反复出现", "每次都", "失望", "不负责任", "一直没", "horrible", "terrible"));
            defaults.put("technical", List.of("崩溃", "闪退", "报错", "错误", "打不开", "转圈", "卡住", "卡死",
                    "失败", "收不到", "没反应", "无法登录", "登录不上", "系统繁忙", "数据不一致", "异常",
                    "error", "crash", "exception", "500", "401", "404"));
            defaults.put("billing", List.of("退款", "退钱", "退回", "退回来", "扣款", "扣了", "扣钱", "多扣", "扣",
                    "账单", "发票", "支付", "优惠券", "抵扣", "自动续费", "续费", "费用", "收费", "金额",
                    "refund", "invoice", "charged"));
            defaults.put("account", List.of("密码", "邮箱", "账户", "账号", "注销", "解绑", "绑定", "换绑", "手机号",
                    "个人信息", "实名", "昵称", "冻结", "资料", "地址簿", "password"));
            defaults.put("request", List.of("帮我", "帮忙", "麻烦", "请帮", "需要修改", "请协助", "取消订单",
                    "改一下", "改成", "重开", "停掉", "催一下", "申请", "please"));
            defaults.put("feedback", List.of("很棒", "满意", "好评", "谢谢", "感谢", "很好用", "点赞", "不错",
                    "专业", "耐心", "解决了", "比上次好"));
            defaults.put("query", List.of("怎么算", "怎么弄", "怎么开", "怎么改", "怎么查", "怎么取消",
                    "如何", "什么时候", "多长时间", "几天", "多久", "多少钱", "几点", "周末",
                    "上班", "能不能", "可以吗", "status"));
            defaults.put("greeting", List.of("你好", "您好", "嗨", "在吗", "有人吗", "早上好", "哈喽", "hello"));
            return defaults;
        }

        public String getSamplesPath() {
            return samplesPath;
        }

        public void setSamplesPath(String samplesPath) {
            this.samplesPath = samplesPath == null || samplesPath.isBlank() ? "classpath:intent-samples.json" : samplesPath;
        }

        public boolean isSemanticEnabled() {
            return semanticEnabled;
        }

        public void setSemanticEnabled(boolean semanticEnabled) {
            this.semanticEnabled = semanticEnabled;
        }

        public double getSemanticThreshold() {
            return semanticThreshold;
        }

        public void setSemanticThreshold(double semanticThreshold) {
            this.semanticThreshold = semanticThreshold;
        }

        public double getSemanticMargin() {
            return semanticMargin;
        }

        public void setSemanticMargin(double semanticMargin) {
            this.semanticMargin = semanticMargin;
        }

        public int getSemanticTopK() {
            return semanticTopK;
        }

        public void setSemanticTopK(int semanticTopK) {
            this.semanticTopK = Math.max(1, semanticTopK);
        }

        public double getWeakScore() {
            return weakScore;
        }

        public void setWeakScore(double weakScore) {
            this.weakScore = weakScore;
        }

        public double getWeakMargin() {
            return weakMargin;
        }

        public void setWeakMargin(double weakMargin) {
            this.weakMargin = weakMargin;
        }

        public double getLlmDirectThreshold() {
            return llmDirectThreshold;
        }

        public void setLlmDirectThreshold(double llmDirectThreshold) {
            this.llmDirectThreshold = llmDirectThreshold;
        }

        public double getMinConfidence() {
            return minConfidence;
        }

        public void setMinConfidence(double minConfidence) {
            this.minConfidence = minConfidence;
        }

        public int getPatternMinHits() {
            return patternMinHits;
        }

        public void setPatternMinHits(int patternMinHits) {
            this.patternMinHits = Math.max(1, patternMinHits);
        }

        public int getCacheHistoryTurns() {
            return cacheHistoryTurns;
        }

        public void setCacheHistoryTurns(int cacheHistoryTurns) {
            this.cacheHistoryTurns = Math.max(0, cacheHistoryTurns);
        }

        public int getCacheMaxEntries() {
            return cacheMaxEntries;
        }

        public void setCacheMaxEntries(int cacheMaxEntries) {
            this.cacheMaxEntries = Math.max(1, cacheMaxEntries);
        }

        public int getCacheTtlSeconds() {
            return cacheTtlSeconds;
        }

        public void setCacheTtlSeconds(int cacheTtlSeconds) {
            this.cacheTtlSeconds = Math.max(0, cacheTtlSeconds);
        }

        public Map<String, List<String>> getPatterns() {
            return patterns;
        }

        public void setPatterns(Map<String, List<String>> patterns) {
            this.patterns = patterns == null || patterns.isEmpty() ? defaultPatterns() : new LinkedHashMap<>(patterns);
        }
    }

    public static class Monitor {
        private double successRateThreshold = 0.90;
        private double latencyMsThreshold = 3000;
        private String webhookUrl = "";

        public double getSuccessRateThreshold() {
            return successRateThreshold;
        }

        public void setSuccessRateThreshold(double successRateThreshold) {
            this.successRateThreshold = successRateThreshold;
        }

        public double getLatencyMsThreshold() {
            return latencyMsThreshold;
        }

        public void setLatencyMsThreshold(double latencyMsThreshold) {
            this.latencyMsThreshold = latencyMsThreshold;
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }
    }

    public static class Eval {
        private String baselinePath = "data/eval/baseline.json";

        public String getBaselinePath() {
            return baselinePath;
        }

        public void setBaselinePath(String baselinePath) {
            this.baselinePath = baselinePath;
        }
    }

    public static class Skills {
        private String rootDir = "skills";
        private int maxPromptChars = 5000;
        private int maxSkillChars = 3200;
        private boolean legacyFilesEnabled = false;
        private long maxFileBytes = 262144;
        private int maxFiles = 128;
        private int maxDepth = 8;
        private boolean allowEmptyReload = false;

        public String getRootDir() {
            return rootDir;
        }

        public void setRootDir(String rootDir) {
            this.rootDir = rootDir;
        }

        public int getMaxPromptChars() {
            return maxPromptChars;
        }

        public void setMaxPromptChars(int maxPromptChars) {
            this.maxPromptChars = maxPromptChars;
        }

        public int getMaxSkillChars() {
            return maxSkillChars;
        }

        public void setMaxSkillChars(int maxSkillChars) {
            this.maxSkillChars = maxSkillChars;
        }

        public boolean isLegacyFilesEnabled() { return legacyFilesEnabled; }
        public void setLegacyFilesEnabled(boolean legacyFilesEnabled) { this.legacyFilesEnabled = legacyFilesEnabled; }
        public long getMaxFileBytes() { return maxFileBytes; }
        public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
        public int getMaxFiles() { return maxFiles; }
        public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }
        public int getMaxDepth() { return maxDepth; }
        public void setMaxDepth(int maxDepth) { this.maxDepth = maxDepth; }
        public boolean isAllowEmptyReload() { return allowEmptyReload; }
        public void setAllowEmptyReload(boolean allowEmptyReload) { this.allowEmptyReload = allowEmptyReload; }
    }

    public static class Security {
        /**
         * 管理接口（/knowledge/**、/mcp/**、/eval/**、/skills/reload、/monitor、/metrics）
         * 的 API Key，通过 X-API-Key 请求头传入。为空时管理接口一律拒绝（fail-closed）。
         */
        private String apiKey = "";
        /** CORS 允许的来源列表，为空表示不开放跨域。 */
        private List<String> corsAllowedOrigins = List.of();
        /** 服务端用户凭证；环境变量 JSON 同名用户覆盖 YAML 配置，值不得写入日志。 */
        private Map<String, String> userKeys = new LinkedHashMap<>();
        private Map<String, String> environmentUserKeys = new LinkedHashMap<>();
        private boolean userKeysJsonValid = true;
        private int userRequestsPerMinute = 60;

        public Map<String, String> getUserKeys() {
            if (!userKeysJsonValid) return Map.of();
            Map<String, String> combined = new LinkedHashMap<>(userKeys);
            combined.putAll(environmentUserKeys);
            return java.util.Collections.unmodifiableMap(combined);
        }

        public void setUserKeys(Map<String, String> value) {
            userKeys = value == null ? new LinkedHashMap<>() : new LinkedHashMap<>(value);
        }

        /** A JSON string permits configuring multiple user credentials through .env/Compose. */
        public void setUserKeysJson(String value) {
            if (value == null || value.isBlank()) {
                environmentUserKeys = new LinkedHashMap<>();
                userKeysJsonValid = true;
                return;
            }
            try {
                var object = new com.fasterxml.jackson.databind.ObjectMapper()
                        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
                if (object == null || !object.isObject()) throw new IllegalArgumentException();
                Map<String, String> parsed = new LinkedHashMap<>();
                object.fields().forEachRemaining(entry -> {
                    if (!entry.getValue().isTextual()) throw new IllegalArgumentException();
                    parsed.put(entry.getKey(), entry.getValue().textValue());
                });
                environmentUserKeys = parsed;
                userKeysJsonValid = true;
            } catch (Exception ex) {
                // Boot's BindFailureAnalyzer echoes the rejected property value. Do not throw
                // during binding: invalidate all user credentials, including YAML fallback.
                environmentUserKeys = new LinkedHashMap<>();
                userKeysJsonValid = false;
            }
        }

        public int getUserRequestsPerMinute() { return userRequestsPerMinute; }
        public void setUserRequestsPerMinute(int value) { userRequestsPerMinute = Math.max(1, value); }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey;
        }

        public List<String> getCorsAllowedOrigins() {
            return corsAllowedOrigins;
        }

        public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
            this.corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : corsAllowedOrigins;
        }
    }

    public static class Mcp {
        private boolean enabled = false;
        private boolean autoToolEnabled = true;
        private String protocolVersion = "2025-06-18";
        private long requestTimeoutMs = 30000;
        private int maxToolIterations = 2;
        private int maxToolPromptChars = 6000;
        private Map<String, Server> servers = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAutoToolEnabled() {
            return autoToolEnabled;
        }

        public void setAutoToolEnabled(boolean autoToolEnabled) {
            this.autoToolEnabled = autoToolEnabled;
        }

        public String getProtocolVersion() {
            return protocolVersion;
        }

        public void setProtocolVersion(String protocolVersion) {
            this.protocolVersion = protocolVersion;
        }

        public long getRequestTimeoutMs() {
            return requestTimeoutMs;
        }

        public void setRequestTimeoutMs(long requestTimeoutMs) {
            this.requestTimeoutMs = requestTimeoutMs;
        }

        public int getMaxToolIterations() {
            return maxToolIterations;
        }

        public void setMaxToolIterations(int maxToolIterations) {
            this.maxToolIterations = maxToolIterations;
        }

        public int getMaxToolPromptChars() {
            return maxToolPromptChars;
        }

        public void setMaxToolPromptChars(int maxToolPromptChars) {
            this.maxToolPromptChars = maxToolPromptChars;
        }

        public Map<String, Server> getServers() {
            return servers;
        }

        public void setServers(Map<String, Server> servers) {
            this.servers = servers == null ? new LinkedHashMap<>() : servers;
        }
    }

    public static class Server {
        private boolean enabled = true;
        private String transport = "stdio";
        private String command = "";
        private List<String> args = List.of();
        private Map<String, String> env = new LinkedHashMap<>();
        private String url = "";
        private Map<String, String> headers = new LinkedHashMap<>();
        private String workingDir = "";
        private long timeoutMs = 0;
        private String stdioFormat = "content-length";
        private List<String> allowedTools = List.of();
        private List<String> blockedTools = List.of();
        private List<String> readOnlyTools = List.of();
        private List<String> dangerousTools = List.of();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport == null || transport.isBlank() ? "stdio" : transport;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args == null ? List.of() : args;
        }

        public Map<String, String> getEnv() {
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env == null ? new LinkedHashMap<>() : env;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url == null ? "" : url;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new LinkedHashMap<>() : headers;
        }

        public String getWorkingDir() {
            return workingDir;
        }

        public void setWorkingDir(String workingDir) {
            this.workingDir = workingDir;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getStdioFormat() {
            return stdioFormat;
        }

        public void setStdioFormat(String stdioFormat) {
            this.stdioFormat = stdioFormat == null || stdioFormat.isBlank() ? "content-length" : stdioFormat;
        }

        public List<String> getAllowedTools() {
            return allowedTools;
        }

        public void setAllowedTools(List<String> allowedTools) {
            this.allowedTools = allowedTools == null ? List.of() : allowedTools;
        }

        public List<String> getBlockedTools() {
            return blockedTools;
        }

        public void setBlockedTools(List<String> blockedTools) {
            this.blockedTools = blockedTools == null ? List.of() : blockedTools;
        }

        public List<String> getReadOnlyTools() {
            return readOnlyTools;
        }

        public void setReadOnlyTools(List<String> readOnlyTools) {
            this.readOnlyTools = readOnlyTools == null ? List.of() : readOnlyTools;
        }

        public List<String> getDangerousTools() {
            return dangerousTools;
        }

        public void setDangerousTools(List<String> dangerousTools) {
            this.dangerousTools = dangerousTools == null ? List.of() : dangerousTools;
        }
    }
}
