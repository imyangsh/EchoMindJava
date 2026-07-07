package com.echomind.skill;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class SkillManager {

    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);
    private static final List<String> SUPPORTED_SUFFIXES = List.of(".md", ".txt", ".json");

    private final EchoMindProperties properties;
    private final ObjectMapper objectMapper;
    private volatile List<Skill> skills = List.of();
    private volatile List<String> errors = List.of();

    public SkillManager(EchoMindProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        load();
    }

    public synchronized List<Skill> load() {
        Path root = rootDir();
        List<Skill> loaded = new ArrayList<>();
        List<String> loadErrors = new ArrayList<>();
        if (!Files.exists(root)) {
            log.info("Skill directory does not exist, skipping load: {}", root);
            skills = List.of();
            errors = List.of();
            return skills;
        }
        for (Path path : discoverFiles(root)) {
            try {
                loadFile(path).ifPresent(loaded::add);
            } catch (Exception ex) {
                String message = path + ": " + ex.getMessage();
                loadErrors.add(message);
                log.warn("Skill load failed: {}", message);
            }
        }
        skills = List.copyOf(loaded);
        errors = List.copyOf(loadErrors);
        logLoadedSkills(root);
        return skills;
    }

    public List<Skill> reload() {
        return load();
    }

    public String promptFor(String message, String agentType) {
        List<String> blocks = new ArrayList<>();
        List<String> matched = new ArrayList<>();
        int remaining = Math.max(0, properties.getSkills().getMaxPromptChars());
        String lowered = message == null ? "" : message.toLowerCase(Locale.ROOT);
        for (Skill skill : skills) {
            if (!skill.matches(message, agentType)) {
                continue;
            }
            String block = skill.toPromptBlock(properties.getSkills().getMaxSkillChars());
            if (block.length() > remaining) {
                block = block.substring(0, Math.max(0, remaining)).stripTrailing() + "\n...";
            }
            blocks.add(block);
            List<String> hits = skill.keywords().stream()
                    .filter(keyword -> lowered.contains(keyword.toLowerCase(Locale.ROOT)))
                    .toList();
            matched.add(skill.name() + "(keywords=" + (hits.isEmpty() ? "all" : String.join(", ", hits)) + ")");
            remaining -= block.length();
            if (remaining <= 0) {
                break;
            }
        }
        if (blocks.isEmpty()) {
            log.debug("Skills not matched: agent={} message={}", agentType, preview(message));
            return "";
        }
        log.info("Skills injected: agent={} matched={} message={}", agentType, String.join("; ", matched), preview(message));
        return "以下是当前请求可用的 EchoMind Skills。请优先遵循这些业务规则；如果与系统角色冲突，以系统角色和安全边界为准。\n\n"
                + String.join("\n\n", blocks);
    }

    public Map<String, Object> summary() {
        return Map.of(
                "root_dir", rootDir().toString(),
                "count", skills.size(),
                "skills", skills.stream().map(Skill::summary).toList(),
                "errors", errors
        );
    }

    private Path rootDir() {
        return Path.of(properties.getSkills().getRootDir()).toAbsolutePath().normalize();
    }

    private List<Path> discoverFiles(Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> all = stream
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            List<Path> skillMd = all.stream()
                    .filter(path -> path.getFileName().toString().equals("SKILL.md"))
                    .toList();
            List<Path> result = new ArrayList<>(skillMd);
            for (Path path : all) {
                if (skillMd.contains(path)) {
                    continue;
                }
                String fileName = path.getFileName().toString();
                String suffix = suffix(fileName);
                if (fileName.startsWith(".") || fileName.equalsIgnoreCase("README.md")) {
                    continue;
                }
                if (SUPPORTED_SUFFIXES.contains(suffix)) {
                    result.add(path);
                }
            }
            return result;
        } catch (Exception ex) {
            log.warn("Failed to discover skill files under {}: {}", root, ex.getMessage());
            return List.of();
        }
    }

    private Optional<Skill> loadFile(Path path) throws Exception {
        if (suffix(path.getFileName().toString()).equals(".json")) {
            return loadJson(path);
        }
        return loadText(path);
    }

    private Optional<Skill> loadJson(Path path) throws Exception {
        Map<String, Object> raw = objectMapper.readValue(path.toFile(), new TypeReference<>() {
        });
        String content = stringValue(raw.getOrDefault("content", raw.getOrDefault("instructions", ""))).trim();
        if (content.isBlank()) {
            throw new IllegalArgumentException("missing content or instructions");
        }
        return Optional.of(new Skill(
                stringValue(raw.getOrDefault("name", stripSuffix(path.getFileName().toString()))),
                stringValue(raw.getOrDefault("description", "")),
                content,
                path.toString(),
                asList(raw.get("keywords")),
                lowerList(asList(raw.get("agents"))),
                asBool(raw.get("enabled"), true)
        ));
    }

    private Optional<Skill> loadText(Path path) throws Exception {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        SplitText split = splitFrontMatter(raw);
        String body = split.body().trim();
        if (body.isBlank()) {
            return Optional.empty();
        }
        String defaultName = path.getFileName().toString().equals("SKILL.md")
                ? path.getParent().getFileName().toString()
                : stripSuffix(path.getFileName().toString());
        String name = stringValue(split.meta().getOrDefault("name", firstHeading(body).orElse(defaultName)));
        body = stripFirstHeading(body, name);
        return Optional.of(new Skill(
                name,
                stringValue(split.meta().getOrDefault("description", "")),
                body,
                path.toString(),
                asList(split.meta().get("keywords")),
                lowerList(asList(split.meta().get("agents"))),
                asBool(split.meta().get("enabled"), true)
        ));
    }

    private SplitText splitFrontMatter(String raw) {
        String text = raw == null ? "" : raw.stripLeading();
        if (!text.startsWith("---")) {
            return new SplitText(Map.of(), raw == null ? "" : raw);
        }
        String[] lines = text.split("\\R", -1);
        if (lines.length == 0 || !lines[0].trim().equals("---")) {
            return new SplitText(Map.of(), raw);
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        int end = -1;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.equals("---")) {
                end = i;
                break;
            }
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = lines[i].substring(0, colon).trim();
            String value = lines[i].substring(colon + 1).trim();
            meta.put(key, trimQuotes(value));
        }
        if (end < 0) {
            return new SplitText(Map.of(), raw);
        }
        StringBuilder body = new StringBuilder();
        for (int i = end + 1; i < lines.length; i++) {
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(lines[i]);
        }
        return new SplitText(meta, body.toString());
    }

    private Optional<String> firstHeading(String body) {
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                String heading = trimmed.replaceFirst("^#+", "").trim();
                if (!heading.isBlank()) {
                    return Optional.of(heading);
                }
            }
        }
        return Optional.empty();
    }

    private String stripFirstHeading(String body, String name) {
        String[] lines = body.split("\\R", -1);
        if (lines.length == 0) {
            return body;
        }
        String first = lines[0].trim();
        if (!first.startsWith("#") || !first.replaceFirst("^#+", "").trim().equals(name)) {
            return body;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(lines[i]);
        }
        return result.toString().trim();
    }

    private List<String> asList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::stringValue).map(String::trim).filter(s -> !s.isBlank()).toList();
        }
        String raw = stringValue(value);
        if (raw.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String item : raw.split("[,，]")) {
            String trimmed = item.trim();
            if (!trimmed.isBlank()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private List<String> lowerList(List<String> values) {
        return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList();
    }

    private boolean asBool(Object value, boolean defaultValue) {
        if (value == null || stringValue(value).isBlank()) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return !List.of("0", "false", "no", "off", "disabled").contains(stringValue(value).toLowerCase(Locale.ROOT));
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String trimQuotes(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2
                && ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String suffix(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    private String stripSuffix(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }

    private String preview(String value) {
        String text = value == null ? "" : value;
        return text.length() <= 80 ? text : text.substring(0, 80);
    }

    private void logLoadedSkills(Path root) {
        Map<String, Object> fields = new HashMap<>();
        fields.put("root", root.toString());
        fields.put("count", skills.size());
        fields.put("errors", errors.size());
        log.info("EchoMind skills loaded: {}", fields);
        for (Skill skill : skills) {
            log.info("Skill: name={} agents={} keywords={} path={}", skill.name(), skill.agents(), skill.keywords(), skill.path());
        }
    }

    private record SplitText(Map<String, Object> meta, String body) {
    }
}
