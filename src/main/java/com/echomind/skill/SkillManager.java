package com.echomind.skill;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SkillManager {
    private static final Logger log = LoggerFactory.getLogger(SkillManager.class);
    private final EchoMindProperties properties;
    private final SkillLoader loader;
    private final SkillSelector selector = new SkillSelector();
    private volatile State state = new State(SkillSnapshot.empty(), null);

    public SkillManager(EchoMindProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.loader = new SkillLoader(objectMapper);
    }

    @PostConstruct
    public void init() {
        load();
    }

    /** Compatibility entry point; failed loads return the still-active version. */
    public List<Skill> load() {
        return reloadReport().snapshot().skills();
    }

    public List<Skill> reload() {
        return load();
    }

    /** Publishes a fully validated candidate and its diagnostics in a single state update. */
    public synchronized SkillReloadResult reloadReport() {
        State previous = state;
        Instant attemptedAt = Instant.now();
        String attemptedRoot = properties.getSkills().getRootDir();
        List<String> errors = new ArrayList<>();
        List<Skill> candidates = List.of();
        try {
            if (attemptedRoot == null || attemptedRoot.isBlank()) {
                throw new IllegalArgumentException("skill root-dir must not be empty");
            }
            Path root = Path.of(attemptedRoot).toAbsolutePath().normalize();
            attemptedRoot = root.toString();
            SkillLoader.Candidate candidate = loader.load(root, properties.getSkills());
            candidates = candidate.skills();
            errors.addAll(candidate.errors());
            if (errors.isEmpty() && candidates.isEmpty() && !previous.snapshot().skills().isEmpty()
                    && !properties.getSkills().isAllowEmptyReload()) {
                errors.add("empty reload rejected; the active skill version was preserved (allow-empty-reload=false)");
            }
        } catch (Exception ex) {
            errors.add(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
        boolean success = errors.isEmpty();
        SkillSnapshot active = success
                ? new SkillSnapshot(previous.snapshot().version() + 1, Instant.now(), attemptedRoot, candidates)
                : previous.snapshot();
        SkillReloadResult report = new SkillReloadResult(success, success, attemptedAt,
                attemptedRoot == null ? "" : attemptedRoot, active, errors);
        state = new State(active, report);
        if (success) {
            log.info("Skills loaded: version={} root={} count={}", active.version(), active.rootDir(), active.skills().size());
        } else {
            log.warn("Skill reload rejected: activeVersion={} errors={}", active.version(), errors);
        }
        return report;
    }

    public SkillSnapshot snapshot() {
        return state.snapshot();
    }

    public SkillSelection select(SkillSnapshot snapshot, SkillSelectionContext context) {
        return selector.select(snapshot, context, properties.getSkills().getMaxPromptChars(),
                properties.getSkills().getMaxSkillChars());
    }

    public String promptFor(String message, String agentType) {
        return select(snapshot(), new SkillSelectionContext(message, agentType, List.of(), List.of(), "", "")).prompt();
    }

    public Map<String, Object> summary() {
        State current = state;
        if (current.lastReload() != null) return current.lastReload().summary();
        return new SkillReloadResult(false, false, Instant.EPOCH, "", current.snapshot(), List.of()).summary();
    }

    private record State(SkillSnapshot snapshot, SkillReloadResult lastReload) { }
}
