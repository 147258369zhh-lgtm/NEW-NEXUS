package io.github.mangi.eta.agent.prompt

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentBoundaryStore
import io.github.mangi.eta.config.Prefs
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Marks a transient user-role reminder that must never become durable conversation history. */
internal const val PROMPT_INJECTION_MARKER = "_eta_prompt_injection"

/** Where a fixed prompt may be applied. One template can target several independent scopes. */
internal enum class PromptScopeType(val wireValue: String) {
    GLOBAL("global"),
    MODEL("model"),
    PROJECT("project"),
    CONVERSATION("conversation"),
    SUBAGENT("subagent"),
    MEETING("meeting"),
    MEETING_PARTICIPANT("meeting_participant"),
    CHARACTER("character"),
    AUTOMATION("automation");

    companion object {
        fun fromWire(value: String?): PromptScopeType? =
            entries.firstOrNull { it.wireValue == value }
    }
}

/** Stable identity of one selectable injection target. */
internal data class PromptTarget(
    val type: PromptScopeType,
    val primaryId: String = "",
    val secondaryId: String = "",
) {
    init {
        require(type != PromptScopeType.GLOBAL || (primaryId.isBlank() && secondaryId.isBlank()))
        require(type == PromptScopeType.GLOBAL || primaryId.isNotBlank())
        require(type != PromptScopeType.MODEL || secondaryId.isNotBlank())
        require(type != PromptScopeType.MEETING_PARTICIPANT || secondaryId.isNotBlank())
    }

    val key: String get() = listOf(type.wireValue, primaryId, secondaryId).joinToString("\u001f")

    fun toJson(): JSONObject = JSONObject()
        .put("type", type.wireValue)
        .put("primary", primaryId)
        .put("secondary", secondaryId)

    companion object {
        val GLOBAL = PromptTarget(PromptScopeType.GLOBAL)
        val NEW_CONVERSATION = PromptTarget(PromptScopeType.CONVERSATION, "__nexa_new_conversation_default__")

        fun model(providerId: String, modelId: String) = PromptTarget(
            PromptScopeType.MODEL,
            providerId.trim(),
            modelId.trim(),
        )

        fun conversation(id: String) = PromptTarget(PromptScopeType.CONVERSATION, id.trim())
        fun project(id: String) = PromptTarget(PromptScopeType.PROJECT, id.trim())
        fun subagent(name: String) = PromptTarget(PromptScopeType.SUBAGENT, name.trim())
        fun meeting(roomId: String) = PromptTarget(PromptScopeType.MEETING, roomId.trim())
        fun meetingParticipant(roomId: String, profileName: String) = PromptTarget(
            PromptScopeType.MEETING_PARTICIPANT,
            roomId.trim(),
            profileName.trim(),
        )

        fun character(id: String) = PromptTarget(PromptScopeType.CHARACTER, id.trim())
        fun automation(id: String) = PromptTarget(PromptScopeType.AUTOMATION, id.trim())

        fun fromJson(json: JSONObject): PromptTarget? = runCatching {
            val type = PromptScopeType.fromWire(json.optString("type")) ?: return@runCatching null
            PromptTarget(
                type = type,
                primaryId = json.optString("primary").trim(),
                secondaryId = json.optString("secondary").trim(),
            )
        }.getOrNull()
    }
}

/** The object identifiers resolved for one concrete model request. */
internal enum class PromptStage(val wireValue: String, val title: String) {
    AUTO("auto", "随运行确定"),
    UNSUPPORTED("unsupported", "未知阶段（不生效）"),
    NORMAL("normal", "普通对话"),
    MEETING_PLANNING("meeting_planning", "会议规划"),
    MEETING_WORK("meeting_work", "会议工作"),
    MEETING_COORDINATION("meeting_coordination", "会议协调"),
    MEETING_SUMMARY("meeting_summary", "会议汇总"),
    MEETING_INTERVIEW("meeting_interview", "会议访谈"),
    SUBAGENT("subagent", "子 Agent"),
    AUTOMATION("automation", "自动任务");

    companion object {
        fun fromWire(value: String?): PromptStage = entries.firstOrNull { it.wireValue == value }
            ?: if (value.isNullOrBlank()) AUTO else UNSUPPORTED
    }
}

internal data class PromptRunScope(
    val conversationId: String = "",
    val subagentName: String = "",
    val meetingRoomId: String = "",
    val meetingParticipant: String = "",
    val characterId: String = "",
    val automationId: String = "",
    val projectId: String = "",
    val stage: PromptStage = PromptStage.AUTO,
    val meetingHost: Boolean = false,
) {
    val effectiveStage: PromptStage get() = when {
        stage != PromptStage.AUTO -> stage
        subagentName.isNotBlank() -> PromptStage.SUBAGENT
        meetingRoomId.isNotBlank() -> PromptStage.MEETING_WORK
        automationId.isNotBlank() -> PromptStage.AUTOMATION
        else -> PromptStage.NORMAL
    }

    fun withDefaults(
        conversationId: String? = null,
        characterId: String? = null,
        meetingRoomId: String? = null,
        automationId: String? = null,
        projectId: String? = null,
    ): PromptRunScope = copy(
        conversationId = this.conversationId.ifBlank { conversationId.orEmpty().trim() },
        characterId = this.characterId.ifBlank { characterId.orEmpty().trim() },
        meetingRoomId = this.meetingRoomId.ifBlank { meetingRoomId.orEmpty().trim() },
        automationId = this.automationId.ifBlank { automationId.orEmpty().trim() },
        projectId = this.projectId.ifBlank { projectId.orEmpty().trim() },
    )

    fun forSubagent(name: String): PromptRunScope = copy(subagentName = name.trim(), stage = PromptStage.SUBAGENT)

    fun toJson(): JSONObject = JSONObject()
        .put("conversation_id", conversationId)
        .put("subagent_name", subagentName)
        .put("meeting_room_id", meetingRoomId)
        .put("meeting_participant", meetingParticipant)
        .put("character_id", characterId)
        .put("automation_id", automationId)
        .put("project_id", projectId)
        .put("stage", stage.wireValue)
        .put("meeting_host", meetingHost)

    companion object {
        fun fromJson(raw: String?): PromptRunScope = runCatching {
            val json = JSONObject(raw.orEmpty())
            PromptRunScope(
                conversationId = json.optString("conversation_id").take(256),
                subagentName = json.optString("subagent_name").take(256),
                meetingRoomId = json.optString("meeting_room_id").take(256),
                meetingParticipant = json.optString("meeting_participant").take(256),
                characterId = json.optString("character_id").take(256),
                automationId = json.optString("automation_id").take(256),
                projectId = json.optString("project_id").take(256),
                stage = PromptStage.fromWire(json.optString("stage")),
                meetingHost = json.optBoolean("meeting_host", false),
            )
        }.getOrDefault(PromptRunScope())
    }
}

internal enum class PromptBlockPosition(val wireValue: String) {
    SYSTEM_PREFIX("system_prefix"),
    POST_HISTORY("post_history");

    companion object {
        fun fromWire(value: String?): PromptBlockPosition =
            entries.firstOrNull { it.wireValue == value } ?: SYSTEM_PREFIX
    }
}

internal data class PromptBlock(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val enabled: Boolean = true,
    val position: PromptBlockPosition = PromptBlockPosition.SYSTEM_PREFIX,
    val order: Int = 0,
    /** Empty means every stage, preserving all legacy templates. */
    val stages: Set<PromptStage> = emptySet(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("content", content)
        .put("enabled", enabled)
        .put("position", position.wireValue)
        .put("order", order)
        .put("stages", JSONArray(stages.map { it.wireValue }.sorted()))

    companion object {
        fun fromJson(json: JSONObject) = PromptBlock(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = json.optString("title").trim().take(PromptInjectionStore.MAX_BLOCK_TITLE_CHARS),
            content = json.optString("content").trim().take(PromptInjectionStore.MAX_BLOCK_CHARS),
            enabled = json.optBoolean("enabled", true),
            position = PromptBlockPosition.fromWire(json.optString("position")),
            order = json.optInt("order", 0),
            stages = json.optJSONArray("stages")?.let { values ->
                (0 until values.length()).map { PromptStage.fromWire(values.optString(it)) }
                    .filter { it != PromptStage.AUTO }.toSet()
            }.orEmpty(),
        )
    }
}

internal data class PromptTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val blocks: List<PromptBlock> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val source: String = "manual",
    val sourceVersion: Int = 1,
) {
    val characterCount: Int get() = blocks.filter { it.enabled }.sumOf { it.content.length }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("description", description)
        .put("enabled", enabled)
        .put("blocks", JSONArray(blocks.sortedWith(BLOCK_ORDER).map(PromptBlock::toJson)))
        .put("created_at", createdAt)
        .put("updated_at", updatedAt)
        .put("source", source)
        .put("source_version", sourceVersion)

    companion object {
        internal val BLOCK_ORDER = compareBy<PromptBlock>(PromptBlock::order).thenBy(PromptBlock::id)

        fun fromJson(json: JSONObject): PromptTemplate {
            val blocks = json.optJSONArray("blocks") ?: JSONArray()
            return PromptTemplate(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = json.optString("name").trim().take(PromptInjectionStore.MAX_TEMPLATE_NAME_CHARS),
                description = json.optString("description").trim().take(PromptInjectionStore.MAX_DESCRIPTION_CHARS),
                enabled = json.optBoolean("enabled", true),
                blocks = (0 until blocks.length()).mapNotNull { index ->
                    blocks.optJSONObject(index)?.let(PromptBlock::fromJson)
                }.take(PromptInjectionStore.MAX_BLOCKS_PER_TEMPLATE),
                createdAt = json.optLong("created_at", System.currentTimeMillis()),
                updatedAt = json.optLong("updated_at", System.currentTimeMillis()),
                source = json.optString("source").take(80),
                sourceVersion = json.optInt("source_version", 1),
            )
        }
    }
}

internal enum class PromptInheritance(val title: String) {
    APPEND("追加并继承"), REPLACE("替换继承的模板"), DISABLE("停用此范围及继承模板");
    companion object {
        fun fromWire(value: String?): PromptInheritance = entries.firstOrNull { it.name == value }
            ?: if (value.isNullOrBlank()) APPEND else DISABLE
    }
}

internal data class PromptScopeBinding(
    val templateId: String,
    val target: PromptTarget,
    val enabled: Boolean = true,
    val inheritance: PromptInheritance = PromptInheritance.APPEND,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("template_id", templateId)
        .put("target", target.toJson())
        .put("enabled", enabled)
        .put("inheritance", inheritance.name)

    companion object {
        fun fromJson(json: JSONObject): PromptScopeBinding? = runCatching {
            val templateId = json.optString("template_id").trim()
            val target = json.optJSONObject("target")?.let(PromptTarget::fromJson)
            if (templateId.isBlank() || target == null) null else PromptScopeBinding(
                templateId = templateId,
                target = target,
                enabled = json.optBoolean("enabled", true),
                inheritance = PromptInheritance.fromWire(json.optString("inheritance")),
            )
        }.getOrNull()
    }
}

internal data class PromptInjectionConfig(
    val templates: List<PromptTemplate> = emptyList(),
    val bindings: List<PromptScopeBinding> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("version", VERSION)
        .put("templates", JSONArray(templates.map(PromptTemplate::toJson)))
        .put("bindings", JSONArray(bindings.map(PromptScopeBinding::toJson)))

    companion object {
        const val SCHEMA = "eta.prompt-injection"
        const val VERSION = 2

        fun fromJson(raw: String): PromptInjectionConfig? = runCatching {
            val json = JSONObject(raw)
            if (json.optString("schema") != SCHEMA || json.optInt("version") !in 1..VERSION) null else {
                val templateJson = json.optJSONArray("templates") ?: JSONArray()
                val bindingJson = json.optJSONArray("bindings") ?: JSONArray()
                val templates = (0 until templateJson.length()).mapNotNull { index ->
                    templateJson.optJSONObject(index)?.let(PromptTemplate::fromJson)
                }.distinctBy(PromptTemplate::id).take(PromptInjectionStore.MAX_TEMPLATES)
                val ids = templates.mapTo(hashSetOf(), PromptTemplate::id)
                PromptInjectionConfig(
                    templates = templates,
                    bindings = (0 until bindingJson.length()).mapNotNull { index ->
                        bindingJson.optJSONObject(index)?.let(PromptScopeBinding::fromJson)
                    }.filter { it.templateId in ids }
                        .distinctBy { it.target.key }
                        .take(PromptInjectionStore.MAX_BINDINGS),
                )
            }
        }.getOrNull()
    }
}

/** Frozen injection content used for one model request. */
internal data class ResolvedPromptInjection(
    val prefixText: String = "",
    val postHistoryText: String = "",
    val templateIds: List<String> = emptyList(),
    val templateNames: List<String> = emptyList(),
    val scopeLabels: List<String> = emptyList(),
    val skippedScopes: List<String> = emptyList(),
    val fragments: List<PromptFragmentTrace> = emptyList(),
    val stage: PromptStage = PromptStage.NORMAL,
) {
    val characterCount: Int get() = prefixText.length + postHistoryText.length
    val isEmpty: Boolean get() = prefixText.isBlank() && postHistoryText.isBlank()
}

internal object PromptInjectionResolver {
    fun resolve(
        config: PromptInjectionConfig,
        model: AgentModelClient.ModelConfig,
        runScope: PromptRunScope,
        macros: PromptMacroContext = PromptMacroContext(),
    ): ResolvedPromptInjection {
        val skipped = mutableListOf<String>()
        val scopeOrder = buildList {
            add(PromptTarget.GLOBAL to 10)
            fun addScope(type: PromptScopeType, rank: Int, target: () -> PromptTarget) {
                runCatching(target).onSuccess { add(it to rank) }.onFailure { skipped += type.wireValue }
            }
            addScope(PromptScopeType.MODEL, 20) { PromptTarget.model(model.providerId, model.model) }
            runScope.projectId.takeIf(String::isNotBlank)?.let { add(PromptTarget.project(it) to 25) }
            runScope.meetingRoomId.takeIf(String::isNotBlank)?.let { add(PromptTarget.meeting(it) to 30) }
            runScope.characterId.takeIf(String::isNotBlank)?.let { add(PromptTarget.character(it) to 40) }
            runScope.subagentName.takeIf(String::isNotBlank)?.let { add(PromptTarget.subagent(it) to 50) }
            runScope.automationId.takeIf(String::isNotBlank)?.let { add(PromptTarget.automation(it) to 60) }
            runScope.conversationId.takeIf(String::isNotBlank)?.let { add(PromptTarget.conversation(it) to 70) }
            runScope.meetingParticipant.takeIf(String::isNotBlank)?.let { participant ->
                addScope(PromptScopeType.MEETING_PARTICIPANT, 80) {
                    PromptTarget.meetingParticipant(runScope.meetingRoomId, participant)
                }
            }
        }
        val ranks = scopeOrder.associate { it.first.key to it.second }
        val templates = config.templates.associateBy(PromptTemplate::id)
        val traces = mutableListOf<PromptFragmentTrace>()
        val selected = mutableListOf<AppliedTemplate>()
        config.bindings.filter { it.enabled && it.target.key in ranks }
            .sortedBy { ranks.getValue(it.target.key) }.forEach { binding ->
                val template = templates[binding.templateId] ?: return@forEach
                if (!template.enabled && binding.inheritance != PromptInheritance.DISABLE) {
                    template.blocks.forEach { traces += trace(template, binding, it, "方案已停用") }
                    return@forEach
                }
                if (binding.inheritance != PromptInheritance.APPEND) {
                    selected.forEach { prior -> prior.template.blocks.forEach {
                        traces += trace(prior.template, prior.binding, it, "被${scopeLabel(binding.target)}的${binding.inheritance.title}省略")
                    } }
                    selected.clear()
                }
                if (binding.inheritance == PromptInheritance.DISABLE) {
                    template.blocks.forEach { traces += trace(template, binding, it, "此范围停用模板") }
                } else {
                    // The most specific occurrence owns a shared template's scope and budget priority.
                    selected.removeAll { it.template.id == template.id }
                    selected += AppliedTemplate(template, binding, ranks.getValue(binding.target.key))
                }
            }
        val stage = runScope.effectiveStage
        val candidates = mutableListOf<Pair<AppliedTemplate, PromptFragmentTrace>>()
        selected.forEach { applied -> applied.template.blocks.sortedWith(PromptTemplate.BLOCK_ORDER).forEach { block ->
            val reason = when {
                !block.enabled -> "指令块已停用"
                block.content.isBlank() -> "正文为空"
                block.stages.isNotEmpty() && stage !in block.stages -> "阶段不匹配：${stage.title}"
                else -> ""
            }
            if (reason.isNotEmpty()) traces += trace(applied.template, applied.binding, block, reason)
            else {
                val expanded = runCatching { macros.expand(block.content.trim(), model.model, model.systemPrompt) }
                if (expanded.isFailure) traces += trace(applied.template, applied.binding, block, "宏展开失败，未注入")
                else {
                    val text = expanded.getOrThrow()
                    val unresolved = Regex("\\{\\{[^{}]+\\}\\}|<(CHAR|BOT|USER)>", RegexOption.IGNORE_CASE).containsMatchIn(text)
                    if (unresolved) traces += trace(applied.template, applied.binding, block, "缺少宏上下文或宏不受支持，未注入")
                    else candidates += applied to trace(applied.template, applied.binding, block, "已注入", text)
                }
            }
        } }
        val context = model.knownContextWindow ?: 128_000
        // Reserve most of the model window for current input, history, tools and output.
        val embedded = model.isEmbeddedLocalTextModel()
        val systemLimit = if (embedded) (context / 5).coerceIn(240, 900) else (context / 2).coerceIn(1_000, 64_000)
        val postLimit = if (embedded) (context / 10).coerceIn(160, 400) else (context / 4).coerceIn(600, 32_000)
        var systemRemaining = systemLimit
        var postRemaining = postLimit
        val included = mutableSetOf<String>()
        // Allocate complete blocks from specific to inherited scopes; output retains stable scope order.
        candidates.sortedWith(compareByDescending<Pair<AppliedTemplate, PromptFragmentTrace>> { it.first.rank })
            .forEach { (_, fragment) ->
                val size = render(fragment).length + 2
                val available = if (fragment.position == PromptBlockPosition.SYSTEM_PREFIX) systemRemaining else postRemaining
                if (size <= available) {
                    included += fragmentKey(fragment)
                    if (fragment.position == PromptBlockPosition.SYSTEM_PREFIX) systemRemaining -= size else postRemaining -= size
                }
            }
        val effective = candidates.map { (_, fragment) ->
            if (fragmentKey(fragment) in included) fragment else fragment.copy(status = "预算不足，整块省略", content = "")
        }
        traces += effective
        val actual = effective.filter { it.status == "已注入" }
        return ResolvedPromptInjection(
            prefixText = actual.filter { it.position == PromptBlockPosition.SYSTEM_PREFIX }.joinToString("\n", transform = ::render),
            postHistoryText = actual.filter { it.position == PromptBlockPosition.POST_HISTORY }.joinToString("\n", transform = ::render),
            templateIds = actual.map { it.templateId }.distinct(),
            templateNames = actual.map { it.templateName }.distinct(),
            scopeLabels = actual.map { it.scope }.distinct(),
            skippedScopes = skipped.distinct(),
            fragments = traces,
            stage = stage,
        )
    }

    private data class AppliedTemplate(val template: PromptTemplate, val binding: PromptScopeBinding, val rank: Int)

    private fun fragmentKey(fragment: PromptFragmentTrace): String = "${fragment.templateId}\u001f${fragment.blockId}"

    private fun trace(template: PromptTemplate, binding: PromptScopeBinding, block: PromptBlock, status: String, content: String = "") =
        PromptFragmentTrace(template.id, template.name, block.id, block.title, template.source,
            scopeLabel(binding.target), block.position, binding.inheritance, status, content,
            sourceCharacters = content.takeIf(String::isNotEmpty)?.length ?: block.content.length)

    private fun render(fragment: PromptFragmentTrace): String = buildString {
        append("<eta_prompt_template name=").append(JSONObject.quote(fragment.templateName))
        append(" scope=").append(JSONObject.quote(fragment.scope))
        append(" position=").append(JSONObject.quote(fragment.position.wireValue)).append(">\n")
        append("用户配置的行为补充；当前用户明确要求优先，不授予或提升工具权限。\n")
        if (fragment.title.isNotBlank()) append("### ").append(fragment.title.trim()).append('\n')
        append(fragment.content).append("\n</eta_prompt_template>")
    }

    private fun scopeLabel(target: PromptTarget): String = when (target.type) {
        PromptScopeType.GLOBAL -> "全局"
        PromptScopeType.MODEL -> "模型 ${target.primaryId} / ${target.secondaryId}"
        PromptScopeType.PROJECT -> "项目 ${target.primaryId}"
        PromptScopeType.CONVERSATION -> "会话 ${target.primaryId}"
        PromptScopeType.SUBAGENT -> "子 Agent ${target.primaryId}"
        PromptScopeType.MEETING -> "会议室 ${target.primaryId}"
        PromptScopeType.MEETING_PARTICIPANT -> "会议室 ${target.primaryId} / ${target.secondaryId}"
        PromptScopeType.CHARACTER -> "角色 ${target.primaryId}"
        PromptScopeType.AUTOMATION -> "自动任务 ${target.primaryId}"
    }
}

internal object PromptInjectionStore {
    private const val KEY = "agent_prompt_injection_v2"
    internal const val CORRUPT_BACKUP_KEY = "agent_prompt_injection_v2.corrupt_backup"
    internal const val MAX_TEMPLATES = 200
    internal const val MAX_BINDINGS = 1_000
    internal const val MAX_BLOCKS_PER_TEMPLATE = 100
    internal const val MAX_BLOCK_CHARS = 200_000
    internal const val MAX_BLOCK_TITLE_CHARS = 200
    internal const val MAX_TEMPLATE_NAME_CHARS = 200
    internal const val MAX_DESCRIPTION_CHARS = 4_000
    private val lock = Any()

    fun load(): PromptInjectionConfig = synchronized(lock) { loadUnlocked() }

    fun templates(): List<PromptTemplate> = load().templates
    fun bindings(): List<PromptScopeBinding> = load().bindings

    fun template(id: String): PromptTemplate? = load().templates.firstOrNull { it.id == id }

    fun binding(target: PromptTarget): PromptScopeBinding? =
        load().bindings.firstOrNull { it.target.key == target.key }

    /** Snapshot the current default binding once, when an ordinary conversation starts. */
    fun bindNewConversationDefault(conversationId: String): Boolean = synchronized(lock) {
        val current = loadUnlocked()
        val target = PromptTarget.conversation(conversationId)
        if (current.bindings.any { it.target.key == target.key }) return@synchronized true
        val default = current.bindings.firstOrNull { it.target == PromptTarget.NEW_CONVERSATION }
            ?: return@synchronized true
        if (current.bindings.size >= MAX_BINDINGS) return@synchronized false
        persist(current.copy(bindings = current.bindings + default.copy(target = target)))
    }

    fun bindingsFor(templateId: String): List<PromptScopeBinding> =
        load().bindings.filter { it.templateId == templateId }

    fun saveTemplate(template: PromptTemplate): Boolean = synchronized(lock) {
        val normalized = normalize(template)
        if (!PromptConflictAnalyzer.analyzeTemplate(normalized).canSave) return@synchronized false
        val current = loadUnlocked()
        val currentTemplates = current.templates.distinctBy(PromptTemplate::id)
        val existing = currentTemplates.indexOfFirst { it.id == normalized.id }
        val templates = currentTemplates.toMutableList().apply {
            if (existing >= 0) set(existing, normalized) else add(normalized)
        }
        if (templates.size > MAX_TEMPLATES) return@synchronized false
        persist(current.copy(templates = templates))
    }

    fun deleteTemplate(id: String): Boolean = synchronized(lock) {
        val current = loadUnlocked()
        persist(
            current.copy(
                templates = current.templates.filterNot { it.id == id },
                bindings = current.bindings.filterNot { it.templateId == id },
            ),
        )
    }

    /** Replacing the binding for the same exact target enforces one active template per scope. */
    fun bind(templateId: String, target: PromptTarget, inheritance: PromptInheritance = PromptInheritance.APPEND): Boolean = synchronized(lock) {
        val current = loadUnlocked()
        if (current.templates.none { it.id == templateId }) return@synchronized false
        val bindings = (current.bindings.filterNot { it.target.key == target.key } +
            PromptScopeBinding(templateId = templateId, target = target, inheritance = inheritance))
        if (bindings.size > MAX_BINDINGS) return@synchronized false
        persist(current.copy(bindings = bindings))
    }

    fun unbind(target: PromptTarget): Boolean = synchronized(lock) {
        val current = loadUnlocked()
        persist(current.copy(bindings = current.bindings.filterNot { it.target.key == target.key }))
    }

    fun replace(config: PromptInjectionConfig): Boolean = synchronized(lock) {
        val templates = config.templates.distinctBy(PromptTemplate::id).take(MAX_TEMPLATES).map(::normalize)
        val ids = templates.mapTo(hashSetOf(), PromptTemplate::id)
        val normalized = PromptInjectionConfig(
            templates = templates,
            bindings = config.bindings.filter { it.templateId in ids }
                .distinctBy { it.target.key }.take(MAX_BINDINGS),
        )
        if (!PromptConflictAnalyzer.analyzeConfig(normalized).canSave) return@synchronized false
        persist(
            normalized,
        )
    }

    fun exportConfig(): String = load().toJson().toString(2)

    fun importConfig(raw: String): Boolean {
        val config = PromptInjectionConfig.fromJson(raw) ?: return false
        return replace(config)
    }

    fun resolve(model: AgentModelClient.ModelConfig, scope: PromptRunScope, macros: PromptMacroContext = PromptMacroContext()): ResolvedPromptInjection =
        PromptInjectionResolver.resolve(load(), model, scope, macros)

    private fun loadUnlocked(): PromptInjectionConfig {
        val prefs = Prefs.localAgentPreferences() ?: return PromptInjectionConfig()
        val raw = prefs.getString(KEY, null)
        if (raw != null) {
            val parsed = PromptInjectionConfig.fromJson(raw)
            if (parsed == null) backupUnreadable(prefs, raw)
            return parsed ?: PromptInjectionConfig()
        }

        val legacy = AgentBoundaryStore.behavior().trim()
        if (legacy.isBlank()) return PromptInjectionConfig()
        val migrated = PromptTemplate(
            name = "原有行为提示",
            description = "从旧版表达与工作习惯迁移",
            blocks = listOf(
                PromptBlock(
                    title = "表达与工作习惯",
                    content = legacy,
                    order = 10,
                ),
            ),
            source = "legacy-behavior",
        )
        val config = PromptInjectionConfig(
            templates = listOf(migrated),
            bindings = listOf(PromptScopeBinding(migrated.id, PromptTarget.GLOBAL)),
        )
        persist(config)
        return config
    }

    private fun persist(config: PromptInjectionConfig): Boolean {
        val prefs = Prefs.localAgentPreferences() ?: return false
        prefs.getString(KEY, null)?.let { raw -> backupUnreadable(prefs, raw) }
        return prefs.edit().putString(KEY, config.toJson().toString()).commit()
    }

    private fun backupUnreadable(prefs: android.content.SharedPreferences, raw: String) {
        if (PromptInjectionConfig.fromJson(raw) != null) return
        prefs.edit().putString(CORRUPT_BACKUP_KEY, raw).commit()
    }

    private fun normalize(template: PromptTemplate): PromptTemplate = template.copy(
        name = template.name.trim().take(MAX_TEMPLATE_NAME_CHARS),
        description = template.description.trim().take(MAX_DESCRIPTION_CHARS),
        blocks = template.blocks.take(MAX_BLOCKS_PER_TEMPLATE).map { block ->
            block.copy(
                title = block.title.trim().take(MAX_BLOCK_TITLE_CHARS),
                content = block.content.trim().take(MAX_BLOCK_CHARS),
            )
        },
        updatedAt = System.currentTimeMillis(),
    )
}
