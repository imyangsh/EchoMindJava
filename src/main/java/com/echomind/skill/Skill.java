package com.echomind.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A validated, immutable business rule. Display names are separate from stable IDs. */
public record Skill(String name, String description, String content, String path,
                    List<String> keywords, List<String> agents, boolean enabled,
                    String id, int priority, boolean alwaysApply, List<String> intents) {
    public Skill {
        name = text(name);
        description = text(description);
        content = text(content);
        path = text(path);
        id = text(id);
        keywords = normalized(keywords);
        agents = normalized(agents);
        intents = normalized(intents);
    }

    /** Compatibility with callers that create the original seven-field skill. */
    public Skill(String name, String description, String content, String path,
                 List<String> keywords, List<String> agents, boolean enabled) {
        this(name, description, content, path, keywords, agents, enabled, name, 0, false, List.of());
    }

    public boolean matches(String message, String agentType) {
        if (!enabled || !allowsAgent(agentType)) return false;
        return alwaysApply || keywords.isEmpty() || matchesKeywords(message);
    }

    public boolean allowsAgent(String agentType) {
        String role = normalize(agentType);
        return agents.isEmpty() || (!role.isBlank() && agents.contains(role));
    }

    public boolean matchesKeywords(String message) {
        String lowered = normalize(message);
        return keywords.stream().anyMatch(keyword -> containsKeyword(lowered, keyword));
    }

    private static boolean containsKeyword(String message, String keyword) {
        boolean token = keyword.matches("[a-z0-9_]+(?:[-.][a-z0-9_]+)*");
        int offset = 0;
        while (offset <= message.length() - keyword.length()) {
            int at = message.indexOf(keyword, offset);
            if (at < 0) return false;
            int end = at + keyword.length();
            if (!token || ((at == 0 || !asciiToken(message.charAt(at - 1)))
                    && (end == message.length() || !asciiToken(message.charAt(end))))) return true;
            offset = at + 1;
        }
        return false;
    }

    private static boolean asciiToken(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_';
    }

    /** The limit includes the title and description; no rule is cut in mid-sentence. */
    public String toPromptBlock(int maxChars) {
        return new SkillPromptBuilder().render(this, maxChars).text();
    }

    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        result.put("description", description);
        result.put("path", path);
        result.put("keywords", keywords);
        result.put("agents", agents);
        result.put("enabled", enabled);
        result.put("priority", priority);
        result.put("always_apply", alwaysApply);
        result.put("intents", intents);
        result.put("content_chars", content.length());
        return Map.copyOf(result);
    }

    static List<String> normalized(List<String> values) {
        return values == null ? List.of() : values.stream().map(Skill::normalize)
                .filter(value -> !value.isBlank()).distinct().toList();
    }

    static String normalize(String value) {
        return text(value).toLowerCase(Locale.ROOT);
    }

    private static String text(String value) { return value == null ? "" : value.trim(); }
}
