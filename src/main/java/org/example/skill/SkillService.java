package org.example.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class SkillService {

    @Value("${agent.skills-dir:skills}")
    private String skillsDir;

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    @PostConstruct
    public void scan() {
        Path root = Path.of(skillsDir);
        if (!Files.isDirectory(root)) {
            log.warn("技能目录不存在: {},跳过技能加载", root.toAbsolutePath());
            return;
        }
        try (var dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory)
                .map(d -> d.resolve("SKILL.md"))
                .filter(Files::exists)
                .forEach(this::parseSkill);
        } catch (IOException e) {
            log.warn("技能目录扫描失败,应用继续启动: {}", e.getMessage());
        }
        log.info("已加载 {} 个技能: {}", skills.size(), skills.keySet());
    }

    private void parseSkill(Path file) {
        try {
            String content = Files.readString(file);
            if (!content.startsWith("---")) {
                log.warn("SKILL.md 缺少 frontmatter,跳过: {}", file);
                return;
            }
            int end = content.indexOf("\n---", 3);
            if (end < 0) {
                log.warn("frontmatter 未闭合,跳过: {}", file);
                return;
            }
            Map<String, Object> meta = new Yaml().load(content.substring(3, end));
            String name = String.valueOf(meta.get("name"));
            String description = String.valueOf(meta.get("description"));
            String body = content.substring(end + 4).trim();
            skills.put(name, new Skill(name, description, body, file.getParent()));
            log.info("加载技能: {} ({})", name, file.getParent());
        } catch (Exception e) {
            log.warn("解析技能失败,跳过 {}: {}", file, e.getMessage());
        }
    }

    /** Level 1:目录文本,注入 system prompt */
    public String catalogPrompt() {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("可用技能(需要详细步骤时,先调用 load_skill 获取指南):\n");
        skills.values().forEach(s ->
            sb.append("- ").append(s.name()).append(": ").append(s.description()).append("\n"));
        return sb.toString();
    }

    /** Level 2:返回完整 SKILL.md 正文 */
    public String load(String name) {
        Skill skill = skills.get(name);
        if (skill == null) {
            return "未找到技能: " + name + "。可用技能: " + skills.keySet();
        }
        return skill.body();
    }

    /** Level 3:读 references/ 文件,必须校验路径防目录穿越 */
    public String readRef(String skillName, String fileName) {
        Skill skill = skills.get(skillName);
        if (skill == null) {
            return "未找到技能: " + skillName;
        }
        try {
            Path base = skill.dir().toRealPath();
            Path target = base.resolve("references").resolve(fileName).normalize();
            if (!target.startsWith(base)) {
                return "非法路径,拒绝访问: " + fileName;
            }
            if (!Files.exists(target)) {
                return "文件不存在: " + fileName;
            }
            return Files.readString(target);
        } catch (IOException e) {
            return "读取失败: " + e.getMessage();
        }
    }
}