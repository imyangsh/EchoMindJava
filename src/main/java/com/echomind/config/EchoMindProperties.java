package com.echomind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "echomind")
public class EchoMindProperties {

    private final Llm llm = new Llm();
    private final Memory memory = new Memory();
    private final Rag rag = new Rag();
    private final Storage storage = new Storage();
    private final Intent intent = new Intent();
    private final Monitor monitor = new Monitor();
    private final Eval eval = new Eval();
    private final Skills skills = new Skills();
    private final Mcp mcp = new Mcp();

    public Llm getLlm() {
        return llm;
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

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
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
            this.minRelevantScore = minRelevantScore;
        }

        public int getMinSources() {
            return minSources;
        }

        public void setMinSources(int minSources) {
            this.minSources = minSources;
        }

        public int getContextWindow() {
            return contextWindow;
        }

        public void setContextWindow(int contextWindow) {
            this.contextWindow = contextWindow;
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
            this.rrfK = rrfK;
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

    public static class Intent {
        private String samplesPath = "classpath:intent-samples.json";
        private double semanticThreshold = 0.42;

        public String getSamplesPath() {
            return samplesPath;
        }

        public void setSamplesPath(String samplesPath) {
            this.samplesPath = samplesPath == null || samplesPath.isBlank() ? "classpath:intent-samples.json" : samplesPath;
        }

        public double getSemanticThreshold() {
            return semanticThreshold;
        }

        public void setSemanticThreshold(double semanticThreshold) {
            this.semanticThreshold = semanticThreshold;
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
