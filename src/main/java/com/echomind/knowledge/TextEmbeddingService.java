package com.echomind.knowledge;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class TextEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(TextEmbeddingService.class);
    private static final int HASH_DIMENSIONS = 1024;
    private static final int MAX_CACHE_SIZE = 4096;
    public static final String HASH_SPACE = "hash:ngram-v1:1024";
    private static final Encoding TOKEN_ENCODING = Encodings.newDefaultEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    private static final Set<String> STOP_WORDS = Set.of("a", "an", "the", "is", "are", "was", "were", "to", "of", "in", "on", "and", "or", "for", "with", "i", "me", "my", "you", "your");

    /**
     * 匹配 ASCII 标点、Unicode 标点（含中文全角标点）与空白。
     * 注意 Java 的 {@code \p{Punct}} 默认是 ASCII-only，必须叠加 {@code \p{IsPunctuation}}
     * 才能切掉中文的「。，、！？（）」等符号。
     */
    private static final String SEPARATOR_REGEX = "[\\p{Punct}\\p{IsPunctuation}\\s]+";

    private final EmbeddingModel embeddingModel;
    private final ConcurrentHashMap<String, EmbeddingVector> cache = new ConcurrentHashMap<>();
    private final String modelId;
    private final int configuredDimensions;
    private final AtomicLong recoveryEpoch = new AtomicLong();
    private volatile String observedSpace = "unobserved";

    //类中存在多个构造器（下面还有一个供 hashOnly() 使用的私有构造器）时，
    //Spring 必须靠 @Autowired 指定注入用哪一个，否则会回退找无参构造器并在启动时报
    //"No default constructor found"
    @Autowired
    public TextEmbeddingService(ObjectProvider<EmbeddingModel> embeddingModelProvider,
                                @Value("${langchain4j.embedding.model-name:external}") String modelId,
                                @Value("${langchain4j.embedding.dimensions:0}") int configuredDimensions) {
        this(resolveEmbeddingModel(embeddingModelProvider), modelId, configuredDimensions);
    }

    public TextEmbeddingService(ObjectProvider<EmbeddingModel> embeddingModelProvider, String modelId) {
        this(resolveEmbeddingModel(embeddingModelProvider), modelId, 0);
    }

    public TextEmbeddingService(ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this(resolveEmbeddingModel(embeddingModelProvider), "external");
    }

    /** bean 定义存在但创建失败时 getIfAvailable() 会抛异常，捕获后降级为 hash embedding。 */
    private static EmbeddingModel resolveEmbeddingModel(ObjectProvider<EmbeddingModel> provider) {
        if (provider == null) {
            return null;
        }
        try {
            return provider.getIfAvailable();
        } catch (Exception ex) {
            log.warn("EmbeddingModel unavailable ({}); falling back to hash embedding", ex.getMessage());
            return null;
        }
    }

    private TextEmbeddingService(EmbeddingModel embeddingModel) {
        this(embeddingModel, "external");
    }

    private TextEmbeddingService(EmbeddingModel embeddingModel, String modelId) {
        this(embeddingModel, modelId, 0);
    }

    private TextEmbeddingService(EmbeddingModel embeddingModel, String modelId, int configuredDimensions) {
        this.embeddingModel = embeddingModel;
        this.modelId = modelId;
        this.configuredDimensions = configuredDimensions;
    }

    public static TextEmbeddingService hashOnly() {
        return new TextEmbeddingService((EmbeddingModel) null);
    }

    public double[] embed(String text) {
        return embedWithMetadata(text).values();
    }

    /** 来源、模型和维度共同定义向量空间；临时 hash 降级不进入真实模型缓存。 */
    public EmbeddingVector embedWithMetadata(String text) {
        String safeText = text == null ? "" : text;
        EmbeddingVector cached = cache.get(safeText);
        if (cached != null) {
            return cached;
        }
        EmbeddingVector vector = computeEmbedding(safeText);
        observe(vector.space());
        if (embeddingModel != null && !vector.external()) {
            return vector;
        }
        if (cache.size() > MAX_CACHE_SIZE) {
            cache.clear();
        }
        cache.put(safeText, vector);
        return vector;
    }

    private EmbeddingVector computeEmbedding(String text) {
        if (embeddingModel != null) {
            try {
                var response = embeddingModel.embed(text);
                float[] output = response == null || response.content() == null ? null : response.content().vector();
                if (output != null && output.length > 0) {
                    double[] values = toDoubleArray(output);
                    if (validValues(values) && (configuredDimensions <= 0 || output.length == configuredDimensions)) {
                        return new EmbeddingVector("external:" + modelId + ":" + output.length, values);
                    }
                }
            } catch (Exception ex) {
                log.debug("EmbeddingModel failed, falling back to hash embedding: {}", ex.getMessage());
            }
        }
        return hashEmbedding(text);
    }

    public EmbeddingVector hashEmbedding(String text) {
        return new EmbeddingVector(HASH_SPACE, hashEmbed(text));
    }

    /** A failed batch falls back as a whole; it never publishes a mixed vector space. */
    public List<EmbeddingVector> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        if (embeddingModel == null) return texts.stream().map(this::embedWithMetadata).toList();
        Map<String, EmbeddingVector> resolved = new HashMap<>();
        for (String text : texts) {
            EmbeddingVector cached = cache.get(text);
            if (cached != null) resolved.put(text, cached);
        }
        List<String> missing = texts.stream().filter(text -> !resolved.containsKey(text)).distinct().toList();
        if (!missing.isEmpty()) {
            try {
                var response = embeddingModel.embedAll(missing.stream().map(TextSegment::from).toList());
                List<float[]> outputs = response == null || response.content() == null ? null
                        : response.content().stream().map(embedding -> embedding == null ? null : embedding.vector()).toList();
                if (outputs == null || outputs.size() != missing.size()) throw new IllegalStateException("Invalid embedding batch size");
                List<EmbeddingVector> computed = new ArrayList<>();
                String space = null;
                for (float[] output : outputs) {
                    if (output == null || !validValues(toDoubleArray(output)) || configuredDimensions > 0 && output.length != configuredDimensions) throw new IllegalStateException("Invalid embedding vector");
                    EmbeddingVector vector = new EmbeddingVector("external:" + modelId + ":" + output.length, toDoubleArray(output));
                    if (space != null && !space.equals(vector.space())) throw new IllegalStateException("Embedding dimension changed within batch");
                    space = vector.space();
                    computed.add(vector);
                }
                if (cache.size() + missing.size() > MAX_CACHE_SIZE) cache.clear();
                for (int i = 0; i < missing.size(); i++) {
                    cache.put(missing.get(i), computed.get(i));
                    resolved.put(missing.get(i), computed.get(i));
                }
                observe(space);
            } catch (Exception ex) {
                observe(HASH_SPACE);
                log.debug("Embedding batch failed; using hash space: {}", ex.getMessage());
                return texts.stream().map(this::hashEmbedding).toList();
            }
        }
        return texts.stream().map(resolved::get).toList();
    }

    public String modelFingerprint() {
        return embeddingModel == null ? HASH_SPACE : embeddingModel.getClass().getName() + ":" + modelId + ":dimensions=" + configuredDimensions;
    }

    public long recoveryEpoch() { return recoveryEpoch.get(); }

    public boolean compatible(EmbeddingVector vector) {
        return vector != null && vector.space() != null && validValues(vector.values()) && (configuredDimensions <= 0 || embeddingModel == null || vector.values().length == configuredDimensions) && (embeddingModel == null
                ? HASH_SPACE.equals(vector.space())
                : vector.space().equals("external:" + modelId + ":" + vector.values().length));
    }

    public void remember(String text, EmbeddingVector vector) {
        if (compatible(vector)) {
            if (cache.size() >= MAX_CACHE_SIZE) cache.clear();
            cache.put(text, vector);
        }
    }

    public void forgetOtherSpace(String text, String space) {
        cache.computeIfPresent(text, (key, vector) -> vector.space().equals(space) ? vector : null);
    }

    private boolean validValues(double[] values) {
        return values != null && values.length > 0 && java.util.Arrays.stream(values).allMatch(Double::isFinite)
                && java.util.Arrays.stream(values).anyMatch(value -> value != 0.0);
    }

    private synchronized void observe(String space) {
        if (!space.equals(observedSpace)) { observedSpace = space; recoveryEpoch.incrementAndGet(); }
    }

    /** CL100K token count, including punctuation and whitespace, without special-token interpretation. */
    public int tokenCount(String text) { return TOKEN_ENCODING.countTokensOrdinary(text == null ? "" : text); }

    public String truncate(String text, int maxTokens) {
        if (text == null || maxTokens <= 0) return "";
        if (tokenCount(text) <= maxTokens) return text;
        int[] points = text.codePoints().toArray();
        int low = 0, high = points.length;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (tokenCount(new String(points, 0, middle)) <= maxTokens) low = middle;
            else high = middle - 1;
        }
        String prefix = new String(points, 0, low);
        while (!prefix.isEmpty() && tokenCount(prefix) > maxTokens) prefix = prefix.substring(0, prefix.offsetByCodePoints(prefix.length(), -1));
        return prefix;
    }

    /** Typed lexical features keep short words separate from Chinese character grams. */
    public List<String> lexicalTokens(String text) {
        List<String> terms = new ArrayList<>();
        for (String segment : segments(text)) {
            if (!STOP_WORDS.contains(segment)) terms.add("w:" + segment);
            for (String run : segment.split("[^\\p{IsHan}]+")) {
                for (int n = 2; n <= 3; n++) {
                    for (int i = 0; i + n <= run.length(); i++) terms.add("g:" + run.substring(i, i + n));
                }
            }
        }
        return terms;
    }

    public double cosine(EmbeddingVector a, EmbeddingVector b) {
        return a == null || b == null || !a.space().equals(b.space()) ? 0.0 : cosine(a.values(), b.values());
    }

    public record EmbeddingVector(String space, double[] values) {
        public boolean external() {
            return space.startsWith("external:");
        }
    }

    public double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0.0;
        }
        if (a.length != b.length) {
            // 不同来源（真实 embedding vs hash 兜底）的向量不在同一空间，
            // 截断比较得到的是纯噪声，直接判为不相似。
            log.warn("Cosine similarity requested for vectors of different dimensions ({} vs {}); returning 0", a.length, b.length);
            return 0.0;
        }
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

    public boolean externalEmbeddingAvailable() {
        return embeddingModel != null;
    }

    private double[] toDoubleArray(float[] output) {
        double[] vector = new double[output.length];
        for (int i = 0; i < output.length; i++) {
            vector[i] = output[i];
        }
        return vector;
    }

    private double[] hashEmbed(String text) {
        double[] vector = new double[HASH_DIMENSIONS];
        Map<String, Integer> termFreq = new HashMap<>();
        for (String token : tokenize(text)) {
            termFreq.merge(token, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
            // 先做一次乘法混淆（黄金比例常数）让高位参与散列，
            // 再用高位取符号、低位取桶下标，两者互相独立。
            // 旧实现直接用 hashCode 的最低位同时决定奇偶桶位和符号，
            // 导致所有向量落入同一象限，语义召回退化为噪声。
            int mixed = entry.getKey().hashCode() * 0x9E3779B1;
            int idx = Math.floorMod(mixed >>> 8, vector.length);
            double sign = ((mixed >>> 31) & 1) == 0 ? 1.0 : -1.0;
            vector[idx] += sign * entry.getValue();
        }
        return vector;
    }

    public List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String segment : segments(text)) {
            tokens.add(segment);
            // n-gram 只在片段内部滑窗，不跨越空格/标点边界，
            // 避免产出 "款 政" 这类跨边界伪 gram。
            for (int n = 2; n <= 3; n++) {
                for (int i = 0; i + n <= segment.length(); i++) {
                    tokens.add(segment.substring(i, i + n));
                }
            }
        }
        return tokens;
    }

    /** 词级片段（按标点/空白切分、NFKC 归一化、小写化），不含 n-gram。 */
    public List<String> segments(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // NFKC 统一全半角（ＡＢＣ→ABC、１２３→123）后再小写化、去标点。
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll(SEPARATOR_REGEX, " ");
        List<String> segments = new ArrayList<>();
        for (String segment : normalized.split("\\s+")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        return segments;
    }
}
