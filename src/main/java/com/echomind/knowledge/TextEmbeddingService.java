package com.echomind.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TextEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(TextEmbeddingService.class);
    private static final int HASH_DIMENSIONS = 256;
    private static final int MAX_CACHE_SIZE = 4096;

    private final EmbeddingModel embeddingModel;
    private final ConcurrentHashMap<String, double[]> cache = new ConcurrentHashMap<>();

    public TextEmbeddingService(ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.embeddingModel = embeddingModelProvider == null ? null : embeddingModelProvider.getIfAvailable();
    }

    private TextEmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public static TextEmbeddingService hashOnly() {
        return new TextEmbeddingService((EmbeddingModel) null);
    }

    public double[] embed(String text) {
        String safeText = text == null ? "" : text;
        double[] cached = cache.get(safeText);
        if (cached != null) {
            return cached;
        }
        double[] vector = computeEmbedding(safeText);
        if (cache.size() > MAX_CACHE_SIZE) {
            cache.clear();
        }
        cache.put(safeText, vector);
        return vector;
    }

    private double[] computeEmbedding(String text) {
        if (embeddingModel != null) {
            try {
                float[] output = embeddingModel.embed(text);
                if (output != null && output.length > 0) {
                    return toDoubleArray(output);
                }
            } catch (Exception ex) {
                log.debug("EmbeddingModel failed, falling back to hash embedding: {}", ex.getMessage());
            }
        }
        return hashEmbed(text);
    }

    public double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0.0;
        }
        int length = Math.min(a.length, b.length);
        double dot = 0.0;
        double na = 0.0;
        double nb = 0.0;
        for (int i = 0; i < length; i++) {
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
        Set<String> grams = new HashSet<>(tokenize(text));
        for (String gram : grams) {
            int hash = gram.hashCode();
            int idx = Math.floorMod(hash, vector.length);
            vector[idx] += (hash & 1) == 0 ? 1.0 : -1.0;
        }
        return vector;
    }

    public List<String> tokenize(String text) {
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
}
