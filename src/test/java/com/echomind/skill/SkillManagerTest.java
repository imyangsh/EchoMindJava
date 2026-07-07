package com.echomind.skill;

import com.echomind.config.EchoMindProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
}
