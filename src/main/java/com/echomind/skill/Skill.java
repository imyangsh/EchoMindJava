package com.echomind.skill;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public record Skill(
        String name,
        String description,
        String content,
        String path,
        List<String> keywords,
        List<String> agents,
        boolean enabled
) {
    public boolean matches(String message, String agentType) {
        if (!enabled) {
            return false;
        }
        String normalizedAgent = normalize(agentType);
        if (!agents.isEmpty() && !normalizedAgent.isBlank() && !agents.contains(normalizedAgent)) {
            return false;
        }
        if (keywords.isEmpty()) {
            return true;
        }
        String lowered = normalize(message);
        return keywords.stream().map(Skill::normalize).anyMatch(lowered::contains);
    }

    public String toPromptBlock(int maxChars) {
        String body = content == null ? "" : content.trim();
        if (body.length() > maxChars) {
            body = body.substring(0, Math.max(0, maxChars)).stripTrailing() + "\n...";
        }
        String descriptionText = description == null || description.isBlank() ? "" : "\n说明: " + description;
        return "### " + name + descriptionText + "\n" + body;
    }

    public Map<String, Object> summary() {
        return Map.of(
                "name", name,
                "description", description,
                "path", path,
                "keywords", keywords,
                "agents", agents,
                "enabled", enabled,
                "content_chars", content == null ? 0 : content.length()
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
