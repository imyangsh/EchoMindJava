package com.echomind.knowledge;

import java.util.Map;

public record SearchResult(
        String id,
        String title,
        String content,
        double score,
        int chunk,
        Map<String, Object> metadata,
        double retrievalScore,
        int rerankRank
) {
    public SearchResult(String id, String title, String content, double score, int chunk, Map<String, Object> metadata) {
        this(id, title, content, score, chunk, metadata, score, 0);
    }

    public double evidenceScore() {
        return score;
    }
}
