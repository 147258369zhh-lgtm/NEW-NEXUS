package io.github.mangi.eta.agent.skill

import androidx.compose.runtime.Immutable

/**
 * 技能索引条目——对应磁盘上一个 [SKILL.md] 文件的元信息。
 */
@Immutable
data class SkillIndexEntry(
    val id: String,
    val name: String,
    val description: String,
    val dependencies: List<String> = emptyList(),
    /** SKILL.md 声明的其他 Skill 依赖；与面向运行环境的能力依赖分开。 */
    val skillDependencies: List<SkillDependencySpec> = emptyList(),
    val compatibility: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val declaration: SkillDeclaration = SkillDeclaration(),
    val rootPath: String,
    val skillFilePath: String,
    val hasScripts: Boolean,
    val hasReferences: Boolean,
    val hasAssets: Boolean,
    val hasEvals: Boolean,
    val enabled: Boolean = true,
    val source: String = "user",
    val installed: Boolean = true,
    val provenance: SkillProvenance? = null,
)

/**
 * SKILL.md 声明的协同信息。优先级只用于解决 Skill 之间的冲突；工具列表是本次 Skill
 * 流程可用的上限，Runtime 仍会与父策略取交集，因此读取 Skill 不会扩大权限。
 */
@Immutable
data class SkillDeclaration(
    val version: String? = null,
    val priority: Int = 0,
    val allowedTools: List<String> = emptyList(),
    val conflictsWith: List<String> = emptyList(),
    /** 无法等价执行的声明必须明确阻止使用，不能把限制丢弃后按无限制运行。 */
    val compatibilityProblems: List<String> = emptyList(),
)

/**
 * 已解析的技能上下文——包含 SKILL.md 正文和附属目录路径。
 */
@Immutable
data class ResolvedSkillContext(
    val skillId: String,
    val frontmatter: Map<String, String>,
    val metadata: Map<String, String> = emptyMap(),
    val bodyMarkdown: String,
    val loadedReferences: List<String> = emptyList(),
    val scriptsDir: String? = null,
    val assetsDir: String? = null,
    val triggerReason: String,
)

@Immutable
data class SkillCompatibilityResult(
    val available: Boolean,
    val reason: String? = null,
)

/**
 * 单次 Agent 运行中解析出的技能上下文集合。
 */
@Immutable
data class SkillContext(
    val installedSkills: List<SkillIndexEntry> = emptyList(),
    /** Explicit composer selection, limited to this run and already validated against the index. */
    val selectedSkillIds: List<String> = emptyList(),
) {
    companion object {
        val EMPTY = SkillContext()
    }
}

/** SKILL.md 文件解析结果。 */
internal data class ParsedSkillFile(
    val frontmatter: Map<String, String>,
    val body: String,
)
