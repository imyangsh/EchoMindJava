package com.echomind.skill;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SkillManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsMarkdownSkillsAndMatchesAgentAndKeywords() throws Exception {
        Path skillDir = tempDir.resolve("technical_support");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: 技术支持处理规范
                description: 技术排障规则
                keywords: 报错,500,error
                agents: technical
                enabled: true
                ---

                # 技术支持处理规范

                请先收集错误码和发生时间。
                """);

        EchoMindProperties properties = new EchoMindProperties();
        properties.getSkills().setRootDir(tempDir.toString());
        SkillManager manager = new SkillManager(properties, new ObjectMapper());

        manager.load();

        assertThat(manager.summary()).containsEntry("count", 1);
        String technicalPrompt = manager.promptFor("登录报错 500", "technical");
        assertThat(technicalPrompt)
                .contains("技术支持处理规范")
                .contains("请先收集错误码");
        assertThat(manager.promptFor("登录报错 500", "billing")).isBlank();
        assertThat(manager.promptFor("普通咨询", "technical")).isBlank();
    }

    @Test
    void loadsStandardYamlListsAndNewMetadataWithBom() throws Exception {
        writeSkill("technical", "\uFEFF" + """
                ---
                id: technical-core
                name: 技术支持
                keywords: [报错, "500"]
                agents:
                  - technical
                intents: [TECHNICAL, request]
                priority: 20
                always_apply: true
                enabled: true
                ---
                # 技术支持
                请先收集错误信息。
                """);
        SkillManager manager = manager(new EchoMindProperties());
        SkillReloadResult result = manager.reloadReport();
        assertThat(result.success()).isTrue();
        Skill skill = result.snapshot().skills().getFirst();
        assertThat(skill.id()).isEqualTo("technical-core");
        assertThat(skill.priority()).isEqualTo(20);
        assertThat(skill.alwaysApply()).isTrue();
        assertThat(skill.intents()).containsExactly("technical", "request");
        assertThat(skill.agents()).containsExactly("technical");
        assertThat(skill.keywords()).containsExactly("报错", "500");
        assertThat(skill.content()).isEqualTo("请先收集错误信息。");
        assertThatThrownBy(() -> skill.keywords().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "enabled: flase", "enabled: 1", "enabled: yes", "always_apply: off", "agents: [unknown]", "intents: [not_an_intent]",
            "priority: 1.5", "always_apply: sometimes", "keywords: [500]", "unexpected: value",
            "name: first\nname: second", "agents: {technical: true}", "id: bad id", "agents:", "priority: null"
    })
    void invalidMetadataPreservesPreviousSnapshot(String metadata) throws Exception {
        Path path = writeSkill("technical", validSkill("请先收集错误码。"));
        SkillManager manager = manager(new EchoMindProperties());
        manager.load();
        SkillSnapshot active = manager.snapshot();
        Files.writeString(path, "---\n" + metadata + "\n---\n新的规则。\n");
        SkillReloadResult result = manager.reloadReport();
        assertThat(result.success()).isFalse();
        assertThat(result.applied()).isFalse();
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.snapshot()).isSameAs(active);
        assertThat(manager.snapshot()).isSameAs(active);
        assertThat(manager.promptFor("报错", "technical")).contains("请先收集错误码。");
    }

    @ParameterizedTest
    @ValueSource(strings = {"---\nagents: technical\n正文没有关闭分隔符", "---\n[technical]\n---\n正文", "# 只有标题", "   "})
    void malformedFrontMatterAndEmptyInstructionsAreReported(String raw) throws Exception {
        writeSkill("bad", raw);
        SkillReloadResult result = manager(new EchoMindProperties()).reloadReport();
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.snapshot().version()).isZero();
    }

    @Test
    void safeYamlRejectsArbitraryObjectTags() throws Exception {
        writeSkill("bad", "---\nname: !!java.net.URL [https://example.invalid]\n---\n正文\n");
        SkillReloadResult result = manager(new EchoMindProperties()).reloadReport();
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void candidateWithAnyErrorCannotPartiallyReplaceActiveSkills() throws Exception {
        Path first = writeSkill("first", validSkill("旧规则。"));
        SkillManager manager = manager(new EchoMindProperties());
        manager.load();
        SkillSnapshot before = manager.snapshot();
        Files.writeString(first, validSkill("新规则。"));
        writeSkill("second", "---\nenabled: invalid\n---\n第二条规则\n");
        SkillReloadResult result = manager.reloadReport();
        assertThat(result.snapshot()).isSameAs(before);
        assertThat(manager.promptFor("报错", "technical")).contains("旧规则。").doesNotContain("新规则。");
    }

    @Test
    void duplicateIdsAreRejected() throws Exception {
        writeSkill("first", "---\nid: duplicate\n---\n第一条规则\n");
        writeSkill("second", "---\nid: duplicate\n---\n第二条规则\n");
        SkillReloadResult result = manager(new EchoMindProperties()).reloadReport();
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(message -> message.contains("duplicate skill id"));
    }

    @Test
    void onlyCanonicalSkillFilesAreLoadedAndPackageResourcesAreSkipped() throws Exception {
        writeSkill("technical", validSkill("有效规则。"));
        writeSkill("technical/references/nested", "无关参考\n");
        writeSkill("technical/scripts/nested", "无关脚本说明\n");
        writeSkill("technical/assets/nested", "无关素材\n");
        writeSkill(".private/nested", "隐藏规则\n");
        Files.writeString(tempDir.resolve("example.md"), "不是独立技能\n");
        Files.writeString(tempDir.resolve("example.json"), "malformed json");
        SkillReloadResult result = manager(new EchoMindProperties()).reloadReport();
        assertThat(result.success()).isTrue();
        assertThat(result.snapshot().skills()).hasSize(1);
        assertThat(result.snapshot().skills().getFirst().id()).isEqualTo("technical");
    }

    @Test
    void legacyFilesRequireExplicitOptInAndJsonIsValidated() throws Exception {
        Files.writeString(tempDir.resolve("legacy.json"), """
                {"id":"legacy","name":"旧格式","instructions":"有效规则。","agents":["technical"],"enabled":true}
                """);
        EchoMindProperties properties = new EchoMindProperties();
        SkillManager manager = manager(properties);
        assertThat(manager.reloadReport().snapshot().skills()).isEmpty();
        properties.getSkills().setLegacyFilesEnabled(true);
        SkillReloadResult legacy = manager.reloadReport();
        assertThat(legacy.success()).isTrue();
        assertThat(legacy.snapshot().skills()).hasSize(1);
        Files.writeString(tempDir.resolve("legacy.json"), "{\"content\":\"first\",\"content\":\"second\"}");
        SkillReloadResult invalid = manager.reloadReport();
        assertThat(invalid.success()).isFalse();
        assertThat(invalid.snapshot()).isSameAs(legacy.snapshot());
    }

    @Test
    void fileCountDepthAndByteLimitsAreEnforced() throws Exception {
        writeSkill("first", validSkill("正文。"));
        writeSkill("second", validSkill("正文。"));
        EchoMindProperties properties = new EchoMindProperties();
        properties.getSkills().setMaxFiles(1);
        SkillManager manager = manager(properties);
        assertThat(manager.reloadReport().errors()).anyMatch(message -> message.contains("max-files"));
        properties.getSkills().setMaxFiles(128);
        properties.getSkills().setMaxDepth(1);
        assertThat(manager.reloadReport().errors()).anyMatch(message -> message.contains("max-depth"));
        properties.getSkills().setMaxDepth(8);
        properties.getSkills().setMaxFileBytes(5);
        assertThat(manager.reloadReport().errors()).anyMatch(message -> message.contains("max-file-bytes"));
    }

    @Test
    void symlinkSkillCannotEscapeTheConfiguredRoot() throws Exception {
        Path root = tempDir.resolve("registry");
        Files.createDirectories(root);
        Path external = tempDir.resolve("external.md");
        Files.writeString(external, validSkill("根目录外规则。"));
        try {
            Files.createSymbolicLink(root.resolve("SKILL.md"), external);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException ex) {
            assumeTrue(false, "symbolic links are unavailable on this test host: " + ex.getMessage());
        }
        EchoMindProperties properties = new EchoMindProperties();
        properties.getSkills().setRootDir(root.toString());
        SkillReloadResult result = new SkillManager(properties, new ObjectMapper()).reloadReport();
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).anyMatch(message -> message.contains("inside the real skill root"));
        assertThat(result.snapshot().skills()).isEmpty();
    }

    @Test
    void malformedUtf8CannotBePublished() throws Exception {
        Path path = writeSkill("bad", "placeholder");
        Files.write(path, new byte[] {(byte) 0xC3, (byte) 0x28});
        SkillReloadResult result = manager(new EchoMindProperties()).reloadReport();
        assertThat(result.success()).isFalse();
        assertThat(result.errors()).isNotEmpty();
    }

    @Test
    void startupAllowsEmptyRegistryButAccidentalEmptyReloadIsRejected() throws Exception {
        EchoMindProperties properties = new EchoMindProperties();
        SkillManager manager = manager(properties);
        assertThat(manager.reloadReport().success()).isTrue();
        Path skill = writeSkill("technical", validSkill("有效规则。"));
        SkillReloadResult loaded = manager.reloadReport();
        Files.delete(skill);
        SkillReloadResult rejected = manager.reloadReport();
        assertThat(rejected.success()).isFalse();
        assertThat(rejected.snapshot()).isSameAs(loaded.snapshot());
        properties.getSkills().setAllowEmptyReload(true);
        SkillReloadResult cleared = manager.reloadReport();
        assertThat(cleared.success()).isTrue();
        assertThat(cleared.snapshot().skills()).isEmpty();
    }

    @Test
    void explicitDisablingAllSkillsCanBePublished() throws Exception {
        Path path = writeSkill("technical", validSkill("有效规则。"));
        SkillManager manager = manager(new EchoMindProperties());
        manager.load();
        Files.writeString(path, "---\nenabled: false\nagents: technical\n---\n已停用规则\n");
        SkillReloadResult result = manager.reloadReport();
        assertThat(result.success()).isTrue();
        assertThat(result.snapshot().skills()).hasSize(1);
        assertThat(result.snapshot().skills().getFirst().enabled()).isFalse();
        assertThat(manager.promptFor("报错", "technical")).isEmpty();
    }

    @Test
    void discoveryFailureAndMissingDirectoryPreserveActiveVersion() throws Exception {
        writeSkill("technical", validSkill("有效规则。"));
        EchoMindProperties properties = new EchoMindProperties();
        SkillManager manager = manager(properties);
        manager.load();
        SkillSnapshot before = manager.snapshot();
        Path ordinaryFile = tempDir.resolve("not-directory");
        Files.writeString(ordinaryFile, "file");
        properties.getSkills().setRootDir(ordinaryFile.toString());
        SkillReloadResult failed = manager.reloadReport();
        assertThat(failed.success()).isFalse();
        assertThat(failed.errors()).anyMatch(message -> message.contains("directory"));
        assertThat(failed.snapshot()).isSameAs(before);
        properties.getSkills().setRootDir(tempDir.resolve("missing").toString());
        properties.getSkills().setAllowEmptyReload(true);
        SkillReloadResult missing = manager.reloadReport();
        assertThat(missing.success()).isFalse();
        assertThat(missing.errors()).anyMatch(message -> message.contains("does not exist"));
        assertThat(missing.snapshot()).isSameAs(before);
    }

    @Test
    void reloadReportsRemainBoundToTheirOwnVersion() throws Exception {
        Path path = writeSkill("technical", validSkill("第一版规则。"));
        SkillManager manager = manager(new EchoMindProperties());
        SkillReloadResult first = manager.reloadReport();
        Files.writeString(path, validSkill("第二版规则。"));
        SkillReloadResult second = manager.reloadReport();
        assertThat(second.snapshot().version()).isEqualTo(first.snapshot().version() + 1);
        assertThat(first.summary()).containsEntry("version", first.snapshot().version());
        assertThat(first.snapshot().skills().getFirst().content()).contains("第一版");
        assertThat(manager.summary()).containsEntry("version", second.snapshot().version());
    }

    @Test
    void concurrentSummaryReadsHaveConsistentCountsAndLists() throws Exception {
        writeSkill("technical", validSkill("有效规则。"));
        SkillManager manager = manager(new EchoMindProperties());
        manager.load();
        CompletableFuture<Void> reloads = CompletableFuture.runAsync(() -> {
            for (int i = 0; i < 20; i++) manager.reloadReport();
        });
        for (int i = 0; i < 200; i++) {
            Map<String, Object> summary = manager.summary();
            assertThat(summary.get("count")).isEqualTo(((List<?>) summary.get("skills")).size());
            assertThat(summary.get("version")).isNotNull();
        }
        reloads.join();
    }

    private SkillManager manager(EchoMindProperties properties) {
        properties.getSkills().setRootDir(tempDir.toString());
        return new SkillManager(properties, new ObjectMapper());
    }

    private Path writeSkill(String directory, String raw) throws Exception {
        Path path = tempDir.resolve(directory).resolve("SKILL.md");
        Files.createDirectories(path.getParent());
        Files.writeString(path, raw);
        return path;
    }

    private String validSkill(String body) {
        return "---\nname: 技术规则\nagents: technical\nkeywords: 报错,500\nenabled: true\n---\n" + body + "\n";
    }
}
