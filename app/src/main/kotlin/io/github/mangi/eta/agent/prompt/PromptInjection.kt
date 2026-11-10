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

        fun model(providerId: String, modelId: String) = PromptTarget(
            PromptScopeType.MODEL,
            providerId.trim(),
            modelId.trim(),
        )

        fun conversation(id: String) = PromptTarget(PromptScopeType.CONVERSATION, id.trim())
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
internal data class PromptRunScope(
    val conversationId: String = "",
    val subagentName: String = "",
    val meetingRoomId: String = "",
    val meetingParticipant: String = "",
    val characterId: String = "",
    val automationId: String = "",
) {
    fun withDefaults(
        conversationId: String? = null,
        characterId: String? = null,
        meetingRoomId: String? = null,
        automationId: String? = null,
    ): PromptRunScope = copy(
        conversationId = this.conversationId.ifBlank { conversationId.orEmpty().trim() },
        characterId = this.characterId.ifBlank { characterId.orEmpty().trim() },
        meetingRoomId = this.meetingRoomId.ifBlank { meetingRoomId.orEmpty().trim() },
        automationId = this.automationId.ifBlank { automationId.orEmpty().trim() },
    )

    fun forSubagent(name: String): PromptRunScope = copy(subagentName = name.trim())

    fun toJson(): JSONObject = JSONObject()
        .put("conversation_id", conversationId)
        .put("subagent_name", subagentName)
        .put("meeting_room_id", meetingRoomId)
        .put("meeting_participant", meetingParticipant)
        .put("character_id", characterId)
        .put("automation_id", automationId)

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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("content", content)
        .put("enabled", enabled)
        .put("position", position.wireValue)
        .put("order", order)

    companion object {
        fun fromJson(json: JSONObject) = PromptBlock(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = json.optString("title").trim().take(PromptInjectionStore.MAX_BLOCK_TITLE_CHARS),
            content = json.optString("content").trim().take(PromptInjectionStore.MAX_BLOCK_CHARS),
            enabled = json.optBoolean("enabled", true),
            position = PromptBlockPosition.fromWire(json.optString("position")),
            order = json.optInt("order", 0),
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

internal data class PromptScopeBinding(
    val templateId: String,
    val target: PromptTarget,
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("template_id", templateId)
        .put("target", target.toJson())
        .put("enabled", enabled)

    companion object {
        fun fromJson(json: JSONObject): PromptScopeBinding? = runCatching {
            val templateId = json.optString("template_id").trim()
            val target = json.optJSONObject("target")?.let(PromptTarget::fromJson)
            if (templateId.isBlank() || target == null) null else PromptScopeBinding(
                templateId = templateId,
                target = target,
                enabled = json.optBoolean("enabled", true),
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
        const val VERSION = 1

        fun fromJson(raw: String): PromptInjectionConfig? = runCatching {
            val json = JSONObject(raw)
            if (json.optString("schema") != SCHEMA || json.optInt("version") != VERSION) null else {
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
) {
    val characterCount: Int get() = prefixText.length + postHistoryText.length
    val isEmpty: Boolean get() = prefixText.isBlank() && postHistoryText.isBlank()
}

internal object PromptInjectionResolver {
    private const val TOTAL_SYSTEM_CHARS = 64_000
    private const val TOTAL_POST_HISTORY_CHARS = 32_000

    fun resolve(
        config: PromptInjectionConfig,
        model: AgentModelClient.ModelConfig,
        runScope: PromptRunScope,
    ): ResolvedPromptInjection {
        val skippedScopes = mutableListOf<String>()
        val scopeOrder = buildList {
            add(PromptTarget.GLOBAL to 10)
            fun addScope(type: PromptScopeType, rank: Int, target: () -> PromptTarget) {
                runCatching(target).onSuccess { add(it to rank) }.onFailure { skippedScopes += type.wireValue }
            }
            addScope(PromptScopeType.MODEL, 20) { PromptTarget.model(model.providerId, model.model) }
            runScope.meetingRoomId.takeIf(String::isNotBlank)?.let { roomId ->
                addScope(PromptScopeType.MEETING, 30) { PromptTarget.meeting(roomId) }
            }
            runScope.characterId.takeIf(String::isNotBlank)?.let { characterId ->
                addScope(PromptScopeType.CHARACTER, 40) { PromptTarget.character(characterId) }
            }
            runScope.subagentName.takeIf(String::isNotBlank)?.let { name ->
                addScope(PromptScopeType.SUBAGENT, 50) { PromptTarget.subagent(name) }
            }
            runScope.automationId.takeIf(String::isNotBlank)?.let { automationId ->
                addScope(PromptScopeType.AUTOMATION, 60) { PromptTarget.automation(automationId) }
            }
            runScope.conversationId.takeIf(String::isNotBlank)?.let { conversationId ->
                addScope(PromptScopeType.CONVERSATION, 70) { PromptTarget.conversation(conversationId) }
            }
            runScope.meetingParticipant.takeIf(String::isNotBlank)?.let { participant ->
                addScope(PromptScopeType.MEETING_PARTICIPANT, 80) {
                    PromptTarget.meetingParticipant(runScope.meetingRoomId, participant)
                }
            }
        }
        val rank = scopeOrder.associate { it.first.key to it.second }
        val templates = config.templates.associateBy(PromptTemplate::id)
        val selected = config.bindings
            .asSequence()
            .filter(PromptScopeBinding::enabled)
            .filter { it.target.key in rank }
            .mapNotNull { binding -> templates[binding.templateId]?.takeIf(PromptTemplate::enabled)?.let { template ->
                AppliedTemplate(template, rank.getValue(binding.target.key), scopeLabel(binding.target))
            } }
            .sortedWith(compareBy<AppliedTemplate>(AppliedTemplate::rank).thenBy { it.template.createdAt })
            .distinctBy { it.template.id }
            .toList()
        if (selected.isEmpty()) return ResolvedPromptInjection(skippedScopes = skippedScopes.distinct())

        val prefix = StringBuilder()
        val postHistory = StringBuilder()
        val names = mutableListOf<String>()
        val labels = mutableListOf<String>()
        for (applied in selected) {
            val blocks = applied.template.blocks
                .filter(PromptBlock::enabled)
                .filter { it.content.isNotBlank() }
                .sortedWith(PromptTemplate.BLOCK_ORDER)
            if (blocks.isEmpty()) continue
            names += applied.template.name
            labels += applied.scopeLabel
            blocks.groupBy(PromptBlock::position).forEach { (position, positionedBlocks) ->
                val target = if (position == PromptBlockPosition.POST_HISTORY) postHistory else prefix
                if (position == PromptBlockPosition.POST_HISTORY) {
                    appendPostHistoryHeader(postHistory, applied)
                } else {
                    appendTemplateHeader(prefix, applied)
                }
                positionedBlocks.forEach { block ->
                    if (block.title.isNotBlank()) target.append("\n### ").append(block.title.trim()).append('\n')
                    target.append(block.content.trim()).append('\n')
                }
                target.append("</eta_prompt_template>\n")
            }
        }
        return ResolvedPromptInjection(
            prefixText = trimCompleteSections(prefix.toString(), TOTAL_SYSTEM_CHARS),
            postHistoryText = trimCompleteSections(postHistory.toString(), TOTAL_POST_HISTORY_CHARS),
            templateIds = selected.map { it.template.id },
            templateNames = names.distinct(),
            scopeLabels = labels.distinct(),
            skippedScopes = skippedScopes.distinct(),
        )
    }

    private data class AppliedTemplate(
        val template: PromptTemplate,
        val rank: Int,
        val scopeLabel: String,
    )

    private fun appendTemplateHeader(target: StringBuilder, applied: AppliedTemplate) {
        if (target.isEmpty()) target.append("用户配置的固定提示词模板（不授予工具权限）：\n")
        target.append("\n<eta_prompt_template name=").append(JSONObject.quote(applied.template.name))
            .append(" scope=").append(JSONObject.quote(applied.scopeLabel)).append(">\n")
    }

    private fun appendPostHistoryHeader(target: StringBuilder, applied: AppliedTemplate) {
        if (target.isEmpty()) target.append("用户配置的当前对话后置提示（不授予工具权限）：\n")
        target.append("\n<eta_prompt_template position=\"post_history\" name=").append(JSONObject.quote(applied.template.name))
            .append(" scope=").append(JSONObject.quote(applied.scopeLabel)).append(">\n")
    }

    /** Never cut inside the outer template tag; a complete prior section is safer than malformed XML. */
    private fun trimCompleteSections(raw: String, limit: Int): String {
        val source = raw.trim()
        if (source.length <= limit) return source
        val closing = "</eta_prompt_template>"
        val end = source.lastIndexOf(closing, limit.coerceAtMost(source.length - 1))
        if (end >= 0) return source.substring(0, end + closing.length).trim()
        val firstTemplate = source.indexOf("\n<eta_prompt_template")
        if (firstTemplate < 0) return ""
        val clippedEnd = (limit - closing.length).coerceAtLeast(firstTemplate)
        return source.substring(0, clippedEnd).trimEnd() + closing
    }

    private fun scopeLabel(target: PromptTarget): String = when (target.type) {
        PromptScopeType.GLOBAL -> "全局"
        PromptScopeType.MODEL -> "模型 ${target.primaryId} / ${target.secondaryId}"
        PromptScopeType.CONVERSATION -> "会话 ${target.primaryId}"
        PromptScopeType.SUBAGENT -> "子 Agent ${target.primaryId}"
        PromptScopeType.MEETING -> "会议室 ${target.primaryId}"
        PromptScopeType.MEETING_PARTICIPANT -> "会议室 ${target.primaryId} / ${target.secondaryId}"
        PromptScopeType.CHARACTER -> "角色 ${target.primaryId}"
        PromptScopeType.AUTOMATION -> "自动任务 ${target.primaryId}"
    }
}

/** Local, backup-friendly persistence for prompt templates and their bindings. */
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
    fun bind(templateId: String, target: PromptTarget): Boolean = synchronized(lock) {
        val current = loadUnlocked()
        if (current.templates.none { it.id == templateId }) return@synchronized false
        val bindings = (current.bindings.filterNot { it.target.key == target.key } +
            PromptScopeBinding(templateId = templateId, target = target))
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

    fun resolve(model: AgentModelClient.ModelConfig, scope: PromptRunScope): ResolvedPromptInjection =
        PromptInjectionResolver.resolve(load(), model, scope)

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
