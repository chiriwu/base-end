package org.example.skill;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SkillTools {

    private final SkillService skillService;

    public SkillTools(SkillService skillService) {
        this.skillService = skillService;
    }

    @Tool(description = "加载指定技能的完整操作指南。当任务匹配技能目录中的某条描述时调用")
    public String load_skill(@ToolParam(description = "技能名称,来自技能目录,例如 weather-advisor") String name) {
        log.info("load_skill 工具被调用, name={}", name);
        if (name == null || name.isBlank()) {
            return "技能名不能为空";
        }
        return skillService.load(name);
    }

    @Tool(description = "读取指定技能 references 目录下的参考文件。仅当技能指南明确提示需要查看 references 文件时调用")
    public String read_skill_ref(
            @ToolParam(description = "技能名称,例如 weather-advisor") String skillName,
            @ToolParam(description = "参考文件名,例如 checklist.md,不要带路径分隔符") String fileName) {
        log.info("read_skill_ref 工具被调用, skill={}, file={}", skillName, fileName);
        if (skillName == null || skillName.isBlank() || fileName == null || fileName.isBlank()) {
            return "技能名和文件名都不能为空";
        }
        return skillService.readRef(skillName, fileName);
    }
}