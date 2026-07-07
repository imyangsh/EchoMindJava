package com.echomind.api.dto;

import com.echomind.knowledge.SearchResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Knowledge source used by the answer")
public record KnowledgeSource(
        String id,
        String title,
        double score,
        int chunk,
        String excerpt,
        Map<String, Object> metadata
) {
    public static KnowledgeSource from(SearchResult result) {
        return new KnowledgeSource(
                result.id(),
                result.title(),
                result.score(),
                result.chunk(),
                truncate(result.content(), 240),
                result.metadata() == null ? Map.of() : result.metadata()
        );
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
