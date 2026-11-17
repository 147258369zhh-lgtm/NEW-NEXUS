package io.github.mangi.eta.agent.skill

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.runtime.AgentToolPolicy

/** 读取 SKILL.md 中可解释的协同声明；缺省视为不声明，不产生任何权限。 */
internal object SkillDeclarationParser {
    private val TOOL_KEYS = listOf("allowed-tools", "allowed_tools", "allowedTools", "tools")
    private val CONFLICT_KEYS = listOf("conflicts-with", "conflicts_with", "conflictsWith", "conflicts")

    fun parse(
        frontmatter: Map<String, String>,
        metadata: Map<String, String>,
    ): SkillDeclaration {
        val version = value(frontmatter, metadata, "version", "skill-version")
            ?.take(64)
            ?.takeIf(String::isNotBlank)
        val priority = value(frontmatter, metadata, "priority")
            ?.toIntOrNull()
            ?.coerceIn(-1_000, 1_000)
            ?: 0
        // Presence matters: an explicit empty restriction must not become an unrestricted Skill.
        val declaredTools = TOOL_KEYS.firstNotNullOfOrNull { frontmatter[it] }
            ?: TOOL_KEYS.firstNotNullOfOrNull { metadata[it] }
        val tools = SkillToolCompatibility.parse(declaredTools)
        val conflicts = value(frontmatter, metadata, *CONFLICT_KEYS.toTypedArray())
            ?.let(::parseList)
            .orEmpty()
            .filter { it.length <= 160 }
            .take(16)
        val unsupportedRuntime = buildList {
            if (frontmatter["context"]?.trim() == "fork") {
                add("暂不支持 Skill context: fork 的独立上下文，未改为主会话执行")
            }
            if (frontmatter["hooks"]?.trim()?.let { it.isNotEmpty() && it != "{}" && it != "[]" } == true) {
                add("暂不支持 Skill frontmatter hooks，未忽略守卫后执行")
            }
            if (frontmatter["disable-model-invocation"]?.equals("true", ignoreCase = true) == true) {
                add("暂不支持仅手动触发的 Skill，未改为模型自动调用")
            }
        }
        return SkillDeclaration(
            version = version,
            priority = priority,
            allowedTools = tools.tools + runtimePreparationTools(tools.tools),
            conflictsWith = conflicts,
            compatibilityProblems = tools.problems + unsupportedRuntime + listOfNotNull(frontmatter[SkillParser.PROBLEMS_KEY]),
        )
    }

    /** Preparation is a bounded file write, so read-only Skill declarations never acquire it. */
    private fun runtimePreparationTools(tools: List<String>): List<String> {
        if (tools.isEmpty() || "skills_prepare_runtime" in tools) return emptyList()
        val policy = AgentToolPolicy(tools)
        return if (policy.isAllowed("terminal") || policy.isAllowed("write_file")) listOf("skills_prepare_runtime") else emptyList()
    }

    private fun value(
        frontmatter: Map<String, String>,
        metadata: Map<String, String>,
        vararg keys: String,
    ): String? {
        keys.forEach { key ->
            frontmatter[key]?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        }
        keys.forEach { key ->
            metadata[key]?.trim()?.takeIf(String::isNotBlank)?.let { return it }
        }
        return null
    }

    private fun parseList(raw: String): List<String> =
        raw.removePrefix("[")
            .removeSuffix("]")
            .split(',', ';', '\n')
            .map { it.trim().removePrefix("-").trim().trim('\'', '"') }
            .filter { it.isNotBlank() && it.none(Char::isISOControl) }
            .distinctBy(String::lowercase)
}

/** 一个被本次任务选中的 Skill；只描述流程来源，不代表新的工具授权。 */
@Immutable
data class CoordinatedSkill(
    val id: String,
    val name: String,
    val source: String,
    val origin: String?,
    val version: String?,
    val priority: Int,
    val allowedTools: List<String>,
)

@Immutable
data class SkillConflict(
    val leftId: String,
    val rightId: String,
    val leftPriority: Int,
    val rightPriority: Int,
    /** null 表示优先级相同且用户没有点名，必须本机确认后才能继续。 */
    val winnerId: String?,
)

@Immutable
data class BlockedSkill(
    val id: String,
    val name: String,
    val code: String,
    val message: String,
)

@Immutable
data class SkillCoordinationPlan(
    val selected: List<CoordinatedSkill> = emptyList(),
    val blocked: List<BlockedSkill> = emptyList(),
    val conflicts: List<SkillConflict> = emptyList(),
) {
    val requiresConfirmation: Boolean get() = conflicts.any { it.winnerId == null }

    fun blockFor(identifier: String): BlockedSkill? {
        val normalized = SkillParser.normalizeSkillLookup(identifier)
        if (normalized.isBlank()) return null
        return blocked.firstOrNull {
            SkillParser.normalizeSkillLookup(it.id) == normalized ||
                SkillParser.normalizeSkillLookup(it.name) == normalized
        }
    }

    companion object {
        val EMPTY = SkillCoordinationPlan()
    }
}

/**
 * 同一个任务内选择多个已启用 Skill，并给出可解释的冲突裁决。
 *
 * 规则固定为：用户本轮明确点名 > SKILL.md 声明的 priority > 失败关闭。
 * 任何 Skill 声明的工具都只是本次流程的上限，调用方必须再与父策略取交集。
 */
internal object SkillCoordination {
    const val DEFAULT_LIMIT = 3
    private const val MAX_RANKED = 200

    private val CONTROL_TOOLS = listOf(
        "skills_read",
        "skills_read_resource",
        "skills_list",
        "get_current_context",
        "release_gui",
        "request_user_input",
    )

    fun plan(
        skills: List<SkillIndexEntry>,
        task: String,
        limit: Int = DEFAULT_LIMIT,
        explicitlySelectedIds: List<String> = emptyList(),
    ): SkillCoordinationPlan {
        if (limit <= 0) return SkillCoordinationPlan.EMPTY
        val selectedIds = explicitlySelectedIds.toSet()
        val candidates = if (selectedIds.isNotEmpty()) {
            val byId = skills.associateBy { it.id }
            explicitlySelectedIds.distinct().take(limit).mapNotNull(byId::get)
        } else {
            if (task.isBlank()) return SkillCoordinationPlan.EMPTY
            SkillMatcher.rankForTask(skills, task, limit = skills.size.coerceAtMost(MAX_RANKED))
                .filter { SkillMatcher.score(task, it) > 0 }
                .take(limit)
        }
        if (candidates.isEmpty()) return SkillCoordinationPlan.EMPTY

        val normalizedTask = SkillParser.normalizeSkillLookup(task)
        val explicitlyRequested = candidates.associateWith {
            it.id in selectedIds || mentions(normalizedTask, it)
        }
        val blocked = mutableListOf<BlockedSkill>()
        val conflicts = mutableListOf<SkillConflict>()
        val blockedIds = linkedSetOf<String>()

        for (leftIndex in candidates.indices) {
            for (rightIndex in leftIndex + 1 until candidates.size) {
                val left = candidates[leftIndex]
                val right = candidates[rightIndex]
                if (!declaresConflict(left, right) && !declaresConflict(right, left)) continue
                val leftExplicit = explicitlyRequested[left] == true
                val rightExplicit = explicitlyRequested[right] == true
                val winner = when {
                    leftExplicit && !rightExplicit -> left
                    rightExplicit && !leftExplicit -> right
                    left.declaration.priority > right.declaration.priority -> left
                    right.declaration.priority > left.declaration.priority -> right
                    else -> null
                }
                conflicts += SkillConflict(
                    leftId = left.id,
                    rightId = right.id,
                    leftPriority = left.declaration.priority,
                    rightPriority = right.declaration.priority,
                    winnerId = winner?.id,
                )
                if (winner == null) {
                    blockedIds += left.id
                    blockedIds += right.id
                    blocked += blockedSkill(
                        skill = left,
                        code = "SKILL_CONFLICT_REQUIRES_CONFIRMATION",
                        message = conflictMessage(left, right, null),
                    )
                    blocked += blockedSkill(
                        skill = right,
                        code = "SKILL_CONFLICT_REQUIRES_CONFIRMATION",
                        message = conflictMessage(right, left, null),
                    )
                } else {
                    val loser = if (winner.id == left.id) right else left
                    blockedIds += loser.id
                    blocked += blockedSkill(
                        skill = loser,
                        code = "SKILL_CONFLICT_SUPERSEDED",
                        message = conflictMessage(loser, winner, winner),
                    )
                }
            }
        }

        return SkillCoordinationPlan(
            selected = candidates.filter { it.id !in blockedIds }.map(::coordinatedSkill),
            blocked = blocked.distinctBy { it.id },
            conflicts = conflicts,
        )
    }

    /** Skill 的工具声明与通用控制工具合并；调用方必须再 intersect 父策略。 */
    fun toolPolicy(entry: SkillIndexEntry?): AgentToolPolicy? {
        val tools = entry?.declaration?.allowedTools.orEmpty()
        if (tools.isEmpty()) return null
        return AgentToolPolicy(allowedTools = (tools + CONTROL_TOOLS).distinct())
    }

    fun controlTools(): List<String> = CONTROL_TOOLS

    private fun coordinatedSkill(entry: SkillIndexEntry) = CoordinatedSkill(
        id = entry.id,
        name = entry.name,
        source = entry.source,
        origin = entry.provenance?.let { provenance ->
            listOf(provenance.sourceName, provenance.sourceType)
                .filter(String::isNotBlank)
                .joinToString("/")
                .takeIf(String::isNotBlank)
        },
        version = entry.provenance?.version?.takeIf(String::isNotBlank) ?: entry.declaration.version,
        priority = entry.declaration.priority,
        allowedTools = entry.declaration.allowedTools,
    )

    private fun blockedSkill(
        skill: SkillIndexEntry,
        code: String,
        message: String,
    ) = BlockedSkill(id = skill.id, name = skill.name, code = code, message = message)

    private fun conflictMessage(loser: SkillIndexEntry, other: SkillIndexEntry, winner: SkillIndexEntry?): String =
        if (winner == null) {
            "Skill ${loser.id} 与 ${other.id} 声明互相冲突且优先级相同（${loser.declaration.priority}）；" +
                "必须先调用 request_user_input 让用户选择，本次不自动执行任何一方流程"
        } else {
            "Skill ${loser.id} 与 ${winner.id} 声明互相冲突；按 priority 选择 ${winner.id}" +
                "（${winner.declaration.priority} > ${loser.declaration.priority}），本次不拼接两者的自动流程"
        }

    private fun declaresConflict(source: SkillIndexEntry, target: SkillIndexEntry): Boolean {
        if (source.declaration.conflictsWith.isEmpty()) return false
        val targetNames = setOf(target.id, target.name)
            .map(SkillParser::normalizeSkillLookup)
            .filter(String::isNotBlank)
            .toSet()
        return source.declaration.conflictsWith.any { declared ->
            SkillParser.normalizeSkillLookup(declared) in targetNames
        }
    }

    private fun mentions(normalizedTask: String, entry: SkillIndexEntry): Boolean =
        listOf(entry.id, entry.name)
            .map(SkillParser::normalizeSkillLookup)
            .filter { it.length >= 3 }
            .any(normalizedTask::contains)
}
