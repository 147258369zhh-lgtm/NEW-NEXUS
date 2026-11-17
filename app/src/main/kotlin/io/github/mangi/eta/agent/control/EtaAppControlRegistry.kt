package io.github.mangi.eta.agent.control

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.agent.prompt.PromptInjectionStore
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.agent.memory.MemoryReviewRepository
import io.github.mangi.eta.agent.memory.MemoryAutomationSettings
import io.github.mangi.eta.agent.memory.MemoryAutomationSettingsStore
import io.github.mangi.eta.agent.skill.SkillEvolution
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.runtime.AgentMetricsActivity
import io.github.mangi.eta.agent.runtime.AgentMetricsTrend
import io.github.mangi.eta.agent.runtime.AgentRunMetricsStore
import io.github.mangi.eta.agent.runtime.AgentRunMetricsRecord
import io.github.mangi.eta.agent.mcp.McpServerLifecycleService
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ModelFallbackSettings
import io.github.mangi.eta.data.model.ModelCandidateBinding
import io.github.mangi.eta.data.model.ModelCandidatePurpose
import io.github.mangi.eta.data.model.ModelCandidateSettings
import io.github.mangi.eta.data.model.ModelRoleSettings
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearanceFontFamily
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceSkin
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceTopBarBlurStyle
import io.github.mangi.eta.data.model.normalizeInterfaceScale
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.model.hasUsableConnection
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AutoBackupStore
import io.github.mangi.eta.data.repository.LocalModelRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.fork.learning.LearningRepository
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.fork.learning.LearningSettingsStore
import io.github.mangi.eta.fork.projects.ProjectDraft
import io.github.mangi.eta.fork.projects.ProjectRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import io.github.mangi.eta.agent.permission.AgentPermissionPolicy
import io.github.mangi.eta.data.model.AgentPermissionMode
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.serialization.json.Json
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.SubagentSettings
import java.time.ZoneId

internal enum class EtaControlDanger {
    SAFE,
    WRITE,
    CONFIRM,
}

internal data class EtaControlCapabilityInfo(
    val id: String,
    val title: String,
    val category: String,
    val summary: String,
    val danger: EtaControlDanger,
    val parameters: JSONObject,
    val canRead: Boolean,
)

/** Provider contract for feature modules that own app-shell capabilities. */
internal data class EtaControlRegistration(
    val info: EtaControlCapabilityInfo,
    val read: (suspend (Context) -> JSONObject)? = null,
    val snapshot: (suspend (Context, JSONObject) -> JSONObject)? = null,
    val rollback: (suspend (Context, JSONObject) -> JSONObject)? = null,
    val execute: suspend (Context, JSONObject) -> JSONObject,
)

internal interface EtaControlProvider {
    val providerId: String

    fun capabilities(): List<EtaControlRegistration>
}

internal sealed interface EtaAppControlEvent {
    data class Navigate(val routeKey: String) : EtaAppControlEvent
}

/** Runtime service and the app shell share the main process, so navigation stays in-process. */
internal object EtaAppControlEvents {
    private val channel = Channel<EtaAppControlEvent>(Channel.BUFFERED)
    val events: Flow<EtaAppControlEvent> = channel.receiveAsFlow()

    fun navigate(routeKey: String) {
        channel.trySend(EtaAppControlEvent.Navigate(routeKey))
    }
}

internal class EtaControlException(
    val code: String,
    override val message: String,
) : IllegalArgumentException(message)

/**
 * Stable, model-readable registry for operations owned by the Eta app shell.
 * Feature modules register typed capabilities here instead of growing one central dispatcher.
 */
internal object EtaAppControlRegistry {
    private data class Definition(
        val info: EtaControlCapabilityInfo,
        val read: (suspend (Context) -> JSONObject)? = null,
        val snapshot: (suspend (Context, JSONObject) -> JSONObject)? = null,
        val rollback: (suspend (Context, JSONObject) -> JSONObject)? = null,
        val execute: suspend (Context, JSONObject) -> JSONObject,
    )

    private val lock = Any()
    private val settingsMutationLock = Mutex()
    private val definitions = linkedMapOf<String, Definition>()
    private val installedProviders = linkedSetOf<String>()

    init {
        registerNavigation()
        registerSettings()
        registerStructuredSettings()
        registerProvider(io.github.mangi.eta.agent.packages.PackageControlProvider)
        registerProvider(io.github.mangi.eta.agent.terminal.LinuxResourceControlProvider)
        registerProvider(BackupSettingsControlProvider)
        registerProvider(ModelSettingsControlProvider)
        registerProvider(RuntimePreferenceControlProvider)
        registerProvider(ConnectorSettingsControlProvider)
        registerProvider(ApiConnectorSettingsControlProvider)
        registerProvider(ProviderSettingsControlProvider)
        registerProvider(WebToolsSettingsControlProvider)
        registerReadOnlyState()
        registerModelState()
        registerLocalModels()
        registerMcpState()
        registerApiConnectorState()
        registerSkillState()
        registerPromptState()
        registerSubagentState()
        registerProjectState()
        registerLearningState()
        registerBackupState()
        registerWorkspaceState()
        registerRuntimeState()
    }

    /**
     * Registers a feature-owned group of capabilities once. Providers may call this during
     * application startup; duplicate provider ids are ignored and duplicate capability ids fail
     * closed so two modules cannot silently overwrite each other's public contract.
     */
    fun registerProvider(provider: EtaControlProvider) {
        require(provider.providerId.matches(Regex("[a-z][a-z0-9_.-]{1,63}"))) {
            "Invalid app control provider id: ${provider.providerId}"
        }
        synchronized(lock) {
            if (!installedProviders.add(provider.providerId)) return
        }
        provider.capabilities().forEach { registration ->
            register(
                Definition(
                    info = registration.info,
                    read = registration.read,
                    snapshot = registration.snapshot,
                    rollback = registration.rollback,
                    execute = registration.execute,
                ),
            )
        }
    }

    fun list(category: String = ""): List<EtaControlCapabilityInfo> = synchronized(lock) {
        definitions.values
            .map(Definition::info)
            .filter { category.isBlank() || it.category.equals(category.trim(), ignoreCase = true) }
            .sortedWith(compareBy(EtaControlCapabilityInfo::category, EtaControlCapabilityInfo::title))
    }

    fun search(query: String): List<EtaControlCapabilityInfo> {
        val needle = query.trim()
        if (needle.isEmpty()) return list()
        return synchronized(lock) {
            definitions.values
                .map(Definition::info)
                .filter { info ->
                    listOf(info.id, info.title, info.category, info.summary)
                        .any { it.contains(needle, ignoreCase = true) }
                }
                .sortedBy(EtaControlCapabilityInfo::title)
        }
    }

    fun canUndo(id: String): Boolean = synchronized(lock) { definitions[id.trim()]?.rollback != null }

    suspend fun updateAppearance(context: Context, transform: (AppearanceSettings) -> AppearanceSettings): JSONObject =
        settingsMutationLock.withLock {
            val next = transform(SettingsDataStore.settings().appearance).normalized()
            executeDefinition(context, definition("settings.appearance"), next.toControlJson(),
                currentCoroutineContext()[SettingsControlOrigin], durable = true,
                approvedMode = AgentPermissionPolicy.effectiveMode())
        }

    internal suspend fun saveModel(context: Context, providerId: String, draft: io.github.mangi.eta.data.model.Model): io.github.mangi.eta.data.model.Model =
        settingsMutationLock.withLock {
            val normalized = draft.copy(id = draft.id.ifBlank { java.util.UUID.randomUUID().toString() })
            val parameters = ModelSettingsControlProvider.uiParameters(providerId, normalized)
            var saved: io.github.mangi.eta.data.model.Model? = null
            val definition = definition("settings.model.configure").copy(execute = { _, _ ->
                io.github.mangi.eta.data.repository.ModelRepository.saveModel(providerId, normalized).also { saved = it }
                    .let(ModelSettingsControlProvider::publicModel)
            })
            executeDefinition(context, definition, parameters, currentCoroutineContext()[SettingsControlOrigin], durable = true,
                approvedMode = AgentPermissionPolicy.effectiveMode())
            requireNotNull(saved)
        }

    internal suspend fun setRuntimePreferenceFromUi(context: Context, key: String, enabled: Boolean): JSONObject =
        settingsMutationLock.withLock {
            check(currentCoroutineContext()[SettingsControlOrigin]?.actor == SettingsActorKind.LOCAL_USER) { "LOCAL_UI_REQUIRED" }
            executeDefinition(context, definition(RuntimePreferenceControlProvider.id(key)), JSONObject().put("enabled", enabled),
                currentCoroutineContext()[SettingsControlOrigin], durable = true,
                approvedMode = AgentPermissionPolicy.effectiveMode())
        }

    internal suspend fun mutateOwnedSettingsFromUi(context: Context, id: String, parameters: JSONObject,
        execute: suspend () -> JSONObject): JSONObject = settingsMutationLock.withLock {
        check(currentCoroutineContext()[SettingsControlOrigin]?.actor == SettingsActorKind.LOCAL_USER) { "LOCAL_UI_REQUIRED" }
        check(id in setOf("settings.connector.configure", "settings.provider.configure", "settings.web_tools", "settings.backup.automatic")) { "OWNED_SETTING_UNSUPPORTED" }
        executeDefinition(context, definition(id).copy(execute = { _, _ -> execute() }), parameters,
            currentCoroutineContext()[SettingsControlOrigin], durable = true,
            approvedMode = AgentPermissionPolicy.effectiveMode())
    }

    suspend fun get(context: Context, id: String): JSONObject {
        val definition = definition(id)
        val reader = definition.read ?: throw EtaControlException(
            "CAPABILITY_NOT_READABLE",
            "能力 ${definition.info.title} 只能执行，不能读取当前值",
        )
        val startedAt = SystemClock.elapsedRealtime()
        return try {
            val result = reader(context.applicationContext)
                .put("ok", true)
                .put("id", definition.info.id)
            audit(context, "get", definition.info.id, JSONObject(), startedAt)
                ?.let { result.put("auditId", it) }
            result
        } catch (failure: Throwable) {
            audit(context, "get", definition.info.id, JSONObject(), startedAt, failure.auditCode())
            throw failure
        }
    }

    suspend fun execute(
        context: Context,
        id: String,
        parameters: JSONObject,
    ): JSONObject {
        val definition = definition(id)
        val origin = currentCoroutineContext()[SettingsControlOrigin]
        requireLocalPermissionOwner(id, origin)
        // Remote agents reach a settings write only through an explicit, live grant issued by the
        // local owner; the plane stamps that grant id on the origin after checking scope and expiry.
        if (id.startsWith("settings.") && origin?.actor == SettingsActorKind.REMOTE_AGENT && origin?.grantId == null) {
            throw EtaControlException("REMOTE_SETTINGS_REQUEST_ONLY", "远端 Agent 只能提出设置修改请求")
        }
        requireLiveSettingsGrant(context, origin)
        EtaControlSchema.validate(definition.info.parameters, parameters)
        val permissionMode = AgentPermissionPolicy.effectiveMode()
        val needsApproval = AgentPermissionPolicy.requiresManualAppControl(
            mode = permissionMode,
            isWrite = definition.info.danger == EtaControlDanger.WRITE,
            isConfirm = definition.info.danger == EtaControlDanger.CONFIRM,
            capabilityId = definition.info.id,
            arguments = parameters,
        )
        if (needsApproval) {
            val approved = EtaAppControlConfirmation.awaitApproval(
                definition.info,
                JSONObject(parameters.toString()).put("approval_mode", permissionMode.wireValue),
            )
            if (!approved) {
                throw EtaControlException("USER_CANCELLED", "用户已取消操作，未修改任何数据")
            }
        }
        return if (id.startsWith("settings.")) settingsMutationLock.withLock {
            executeDefinition(context, definition, parameters, origin, durable = true, approvedMode = permissionMode)
        } else executeDefinition(context, definition, parameters, origin, durable = false, approvedMode = permissionMode)
    }

    private fun requireLocalPermissionOwner(id: String, origin: SettingsControlOrigin?) {
        if (id == "settings.agent_permission" && (origin?.actor != SettingsActorKind.LOCAL_USER ||
            io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller != null)) {
            throw EtaControlException("PERMISSION_MODE_LOCAL_USER_ONLY", "审批模式只能由用户在本机设置中更改，Agent 不能自行提权")
        }
    }

    private fun requireLiveSettingsGrant(context: Context, origin: SettingsControlOrigin?) {
        origin?.grantId?.let { grantId ->
            val live = SettingsGrantStore(context).list().any { grant ->
                grant.id == grantId && grant.revokedAt == null &&
                    (grant.consumedAt == null || (grant.singleUse && origin.singleUseClaimed)) &&
                    System.currentTimeMillis() < grant.validUntil
            }
            if (!live) throw EtaControlException("SETTINGS_GRANT_REVOKED", "该设置授权已失效，请重新申请")
        }
    }

    private suspend fun executeDefinition(context: Context, definition: Definition, parameters: JSONObject,
        origin: SettingsControlOrigin?, durable: Boolean, approvedMode: AgentPermissionMode): JSONObject = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val auditStore = EtaAppControlAuditStore(context)
        var pendingId: String? = null
        var executionEntered = false
        try {
            requireLiveSettingsGrant(context, origin)
            val before = snapshotOrRead(definition, context, parameters)
            if (durable) {
                check(definition.rollback == null || before != null) { "SETTINGS_SNAPSHOT_UNAVAILABLE" }
                pendingId = auditStore.append("execute", definition.info.id, parameters, false, durationMs = 0,
                    before = before, origin = origin, phase = "PENDING").getString("id")
            }
            requireLiveSettingsGrant(context, origin)
            if (AgentPermissionPolicy.effectiveMode() != approvedMode) {
                throw EtaControlException("APPROVAL_POLICY_CHANGED", "等待期间审批规则已收紧，请按当前规则重新请求")
            }
            executionEntered = true
            val result = definition.execute(context.applicationContext, parameters)
            if (result.has("ok") && !result.optBoolean("ok")) {
                throw EtaControlException(result.optString("code", "CAPABILITY_FAILED"), result.optString("message", "设置未保存"))
            }
            result.put("ok", true).put("id", definition.info.id)
            val after = snapshotOrRead(definition, context, parameters)
            before?.let { result.put("before", it) }
            after?.let { result.put("after", it) }
            if (durable) {
                check(definition.rollback == null || after != null) { "SETTINGS_RESULT_UNVERIFIED" }
                withContext(NonCancellable) { auditStore.settleMutation(checkNotNull(pendingId),
                    if (before != null && after != null && SettingsControlSnapshots.equal(before, after)) "NO_CHANGE" else "APPLIED",
                    after, SystemClock.elapsedRealtime() - startedAt) }
                result.put("auditId", pendingId)
                    .put("changed", before == null || after == null || !SettingsControlSnapshots.equal(before, after))
            } else audit(context, "execute", definition.info.id, parameters, startedAt, before = before, after = after)
                ?.let { result.put("auditId", it) }
            result
        } catch (failure: Throwable) {
            if (pendingId != null) withContext(NonCancellable) { runCatching {
                auditStore.settleMutation(pendingId, if (executionEntered) "UNCERTAIN" else "FAILED",
                    snapshotOrRead(definition, context, parameters), SystemClock.elapsedRealtime() - startedAt, failure.auditCode())
            } } else audit(context, "execute", definition.info.id, parameters, startedAt, failure.auditCode())
            throw failure
        }
    }

    suspend fun audit(context: Context, limit: Int = 50): JSONObject = withContext(Dispatchers.IO) {
        val store = EtaAppControlAuditStore(context)
        JSONObject()
            .put("ok", true)
            .put("summary", store.summary())
            .put("entries", JSONArray(store.list(limit)))
    }

    suspend fun undo(context: Context, auditId: String): JSONObject = withContext(Dispatchers.IO) {
        val store = EtaAppControlAuditStore(context)
        val entry = store.find(auditId)
            ?: throw EtaControlException("AUDIT_NOT_FOUND", "未找到可撤销的审计记录")
        if (!entry.optBoolean("success") || entry.optBoolean("undone")) {
            throw EtaControlException("AUDIT_NOT_UNDOABLE", "该审计记录已失败或已撤销")
        }
        val definition = definition(entry.optString("capabilityId"))
        requireLocalPermissionOwner(definition.info.id, currentCoroutineContext()[SettingsControlOrigin])
        val rollback = definition.rollback
            ?: throw EtaControlException("ROLLBACK_UNSUPPORTED", "该能力没有声明可逆回滚")
        val before = entry.optJSONObject("before")
            ?: throw EtaControlException("ROLLBACK_SNAPSHOT_MISSING", "审计中没有变更前状态")
        check(entry.optBoolean("rollbackSafe")) { "ROLLBACK_SNAPSHOT_UNVERIFIED" }
        if (currentCoroutineContext()[SettingsControlOrigin]?.actor == SettingsActorKind.REMOTE_AGENT) {
            throw EtaControlException("REMOTE_SETTINGS_REQUEST_ONLY", "远端 Agent 不能撤销本机设置")
        }
        val permissionMode = AgentPermissionPolicy.effectiveMode()
        val needsApproval = AgentPermissionPolicy.requiresManualAppControl(
            mode = permissionMode,
            isWrite = definition.info.danger == EtaControlDanger.WRITE,
            isConfirm = definition.info.danger == EtaControlDanger.CONFIRM,
            capabilityId = definition.info.id,
            arguments = JSONObject().put("auditId", auditId),
        )
        if (needsApproval) {
            if (!EtaAppControlConfirmation.awaitApproval(definition.info,
                    JSONObject().put("auditId", auditId).put("restore", before).put("approval_mode", permissionMode.wireValue))) {
                throw EtaControlException("USER_CANCELLED", "用户已取消撤销")
            }
        }
        settingsMutationLock.withLock {
        val current = snapshotOrRead(definition, context, entry.optJSONObject("parameters") ?: JSONObject())
            ?: throw EtaControlException("ROLLBACK_SNAPSHOT_MISSING", "当前设置不可读取")
        check(SettingsControlSnapshots.hash(current) == entry.optString("afterHash")) { "SETTINGS_CHANGED_SINCE_AUDIT" }
        check(store.find(auditId)?.optBoolean("undone") == false) { "AUDIT_ALREADY_UNDONE" }
        val startedAt = SystemClock.elapsedRealtime()
        val pendingId = store.append("undo", definition.info.id, entry.optJSONObject("parameters") ?: JSONObject(), false,
            durationMs = 0, before = current, origin = currentCoroutineContext()[SettingsControlOrigin], phase = "PENDING").getString("id")
        try {
        val result = rollback(context.applicationContext, before)
            .put("ok", true)
            .put("id", definition.info.id)
            .put("undoneAuditId", auditId)
        val after = snapshotOrRead(
            definition,
            context,
            entry.optJSONObject("parameters") ?: JSONObject(),
        )
        check(after != null && SettingsControlSnapshots.equal(before, after)) { "ROLLBACK_VERIFICATION_FAILED" }
        withContext(NonCancellable) {
            store.settleMutation(pendingId, "APPLIED", after, SystemClock.elapsedRealtime() - startedAt)
            check(store.markUndone(auditId)) { "AUDIT_MARK_UNDONE_FAILED" }
        }
        after?.let { result.put("after", it) }
        result.put("auditId", pendingId)
        result
        } catch (failure: Throwable) {
            withContext(NonCancellable) { runCatching { store.settleMutation(pendingId, "UNCERTAIN",
                snapshotOrRead(definition, context, entry.optJSONObject("parameters") ?: JSONObject()),
                SystemClock.elapsedRealtime() - startedAt, failure.auditCode()) } }
            throw failure
        }
        }
    }

    private suspend fun audit(
        context: Context,
        operation: String,
        capabilityId: String,
        parameters: JSONObject,
        startedAt: Long,
        errorCode: String = "",
        before: JSONObject? = null,
        after: JSONObject? = null,
    ): String? = withContext(NonCancellable + Dispatchers.IO) {
        runCatching {
                EtaAppControlAuditStore(context).append(
                    operation = operation,
                    capabilityId = capabilityId,
                    parameters = parameters,
                    success = errorCode.isBlank(),
                    errorCode = errorCode,
                    durationMs = SystemClock.elapsedRealtime() - startedAt,
                    before = before,
                    after = after,
                ).optString("id").takeIf(String::isNotBlank)
        }.getOrNull()
    }

    private suspend fun readingOrNull(definition: Definition, context: Context): JSONObject? =
        definition.read?.let { reader -> runCatching { reader(context.applicationContext) }.getOrNull() }

    private suspend fun snapshotOrRead(
        definition: Definition,
        context: Context,
        parameters: JSONObject,
    ): JSONObject? = definition.snapshot?.let { reader ->
        runCatching { reader(context.applicationContext, parameters) }.getOrNull()
    } ?: readingOrNull(definition, context)

    private fun Throwable.auditCode(): String = when (this) {
        is EtaControlException -> code
        is kotlinx.coroutines.CancellationException -> "CANCELLED"
        is org.json.JSONException -> "INVALID_ARGUMENT"
        is IllegalArgumentException -> "INVALID_ARGUMENT"
        else -> "CAPABILITY_FAILED"
    }

    private fun definition(id: String): Definition = synchronized(lock) {
        definitions[id.trim()] ?: throw EtaControlException(
            "UNKNOWN_CAPABILITY",
            "未知软件能力：${id.trim().ifBlank { "(empty)" }}",
        )
    }

    private fun register(definition: Definition) {
        val id = definition.info.id
        require(id.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+"))) {
            "Invalid app control capability id: $id"
        }
        synchronized(lock) {
            check(definitions.put(id, definition) == null) { "Duplicate app control capability: $id" }
        }
    }

    private fun registerReadOnly(
        id: String,
        title: String,
        category: String,
        summary: String,
        read: suspend (Context) -> JSONObject,
    ) {
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = id,
                    title = title,
                    category = category,
                    summary = summary,
                    danger = EtaControlDanger.SAFE,
                    parameters = JSONObject(),
                    canRead = true,
                ),
                read = read,
                execute = { _, _ ->
                    throw EtaControlException("CAPABILITY_READ_ONLY", "${title}只能读取")
                },
            ),
        )
    }

    private fun registerNavigation() {
        fun navigation(id: String, title: String, category: String, summary: String, routeKey: String) {
            register(
                Definition(
                    info = EtaControlCapabilityInfo(
                        id = id,
                        title = title,
                        category = category,
                        summary = summary,
                        danger = EtaControlDanger.SAFE,
                        parameters = JSONObject(),
                        canRead = false,
                    ),
                    execute = { _, _ ->
                        EtaAppControlEvents.navigate(routeKey)
                        JSONObject().put("routeKey", routeKey).put("opened", true)
                    },
                ),
            )
        }

        fun dynamicNavigation(
            id: String,
            title: String,
            category: String,
            summary: String,
            parameter: String,
            routeKey: (String) -> String,
        ) {
            register(
                Definition(
                    info = EtaControlCapabilityInfo(
                        id = id,
                        title = title,
                        category = category,
                        summary = summary,
                        danger = EtaControlDanger.SAFE,
                        parameters = JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject().put(parameter, JSONObject().put("type", "string")),
                            )
                            .put("required", JSONArray().put(parameter)),
                        canRead = false,
                    ),
                    execute = { _, parameters ->
                        val value = parameters.optString(parameter).trim()
                        require(value.isNotEmpty() && value.length <= 256 && value.none(Char::isISOControl)) {
                            "$parameter 无效"
                        }
                        val key = routeKey(value)
                        EtaAppControlEvents.navigate(key)
                        JSONObject().put("routeKey", key).put("opened", true)
                    },
                ),
            )
        }

        navigation("navigation.home", "返回对话", "导航", "打开主对话页面", "home")
        navigation("navigation.models", "模型提供商", "模型", "管理在线模型、备用模型和提供商", "models")
        navigation("navigation.local_models", "本地模型", "模型", "打开本地文本、视觉、语音和向量模型中心", "local_models")
        navigation("navigation.memory", "记忆与自学习", "智能", "查看分层记忆、整理候选与自学习策略", "memory")
        navigation("navigation.capabilities", "能力中心", "智能", "管理 Skill、MCP、连接器和工具边界", "capabilities")
        navigation("navigation.skills", "Skills", "智能", "管理已安装 Skill", "skills")
        navigation("navigation.mcp", "MCP 服务", "智能", "管理 MCP 服务及工具授权", "mcp")
        navigation("navigation.prompt_injection", "提示词注入", "智能", "管理全局、Agent、会话与会议室提示词", "prompt_injection")
        navigation("navigation.api_connectors", "REST API 连接", "智能", "管理 OAuth 与 REST API 外部能力", "api_connectors")
        navigation("navigation.automations", "自动任务", "自动化", "创建和查看自动任务及运行记录", "automations")
        navigation("navigation.subagents", "子 Agent", "智能", "管理子 Agent、角色指令和工具权限", "subagents")
        navigation("navigation.learning", "自学习策略", "智能", "管理经验提炼、验证和规则晋升", "learning")
        navigation("navigation.a2a", "A2A Agent", "自动化", "连接其他设备或 Agent", "a2a")
        navigation("navigation.metrics", "运行统计", "统计", "查看 Token、工具执行与运行轨迹", "metrics")
        navigation("navigation.permissions", "权限与边界", "系统", "检查无障碍、Root、悬浮窗和工具权限", "permissions")
        navigation("navigation.speech", "语音设置", "语音", "设置语音识别、合成与自动朗读", "speech")
        navigation("navigation.speech_recognition", "语音识别", "语音", "设置识别模型、唤醒词与声纹", "speech_recognition")
        navigation("navigation.speech_synthesis", "语音合成", "语音", "设置合成模型、音色与播放", "speech_synthesis")
        navigation("navigation.system_enhance", "系统增强", "系统", "管理接管、无障碍与系统增强能力", "system_enhance")
        navigation("navigation.characters", "角色", "智能", "管理角色、人设与剧情记忆", "characters")
        navigation("navigation.workspace", "项目文件", "文件", "打开项目文件与共享目录", "workspace")
        navigation("navigation.projects", "项目", "文件", "管理项目专属文件夹", "projects")
        navigation("navigation.shared_folders", "共享文件夹", "文件", "管理 Android 与 Linux 工作区共享目录", "shared_folders")
        navigation("navigation.backup", "数据备份", "系统", "导出、导入、自动备份与恢复", "backup")
        navigation("navigation.terminal", "终端", "系统", "打开设备或 Linux 终端", "terminal")
        navigation("navigation.settings", "设置", "系统", "打开全部软件设置", "settings")
        dynamicNavigation(
            id = "navigation.model_provider",
            title = "打开模型提供商",
            category = "模型",
            summary = "按提供商 ID 打开模型提供商详情",
            parameter = "providerId",
        ) { "model_provider:$it" }
        dynamicNavigation(
            id = "navigation.mcp_server",
            title = "打开 MCP 服务",
            category = "智能",
            summary = "按服务 ID 打开 MCP 详情与工具目录",
            parameter = "serverId",
        ) { "mcp_server:$it" }
        dynamicNavigation(
            id = "navigation.meeting_room",
            title = "打开会议室",
            category = "智能",
            summary = "按会议室 ID 打开对话空间",
            parameter = "roomId",
        ) { "meeting_room:$it" }
    }

    private fun registerSettings() {
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.memory.enabled",
                    title = "记忆开关",
                    category = "设置",
                    summary = "读取或修改全局记忆注入与读写开关",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "enabled",
                                JSONObject().put("type", "boolean").put("description", "新的记忆开关状态"),
                            ),
                        )
                        .put("required", org.json.JSONArray().put("enabled")),
                    canRead = true,
                ),
                read = {
                    JSONObject().put("enabled", SettingsDataStore.settings().memoryEnabled)
                },
                rollback = { _, before ->
                    val enabled = before.getBoolean("enabled")
                    SettingsDataStore.updateSettings { it.copy(memoryEnabled = enabled) }
                    JSONObject().put("enabled", enabled)
                },
                execute = { _, parameters ->
                    require(parameters.has("enabled")) { "缺少 enabled" }
                    val enabled = parameters.getBoolean("enabled")
                    SettingsDataStore.updateSettings { it.copy(memoryEnabled = enabled) }
                    JSONObject().put("enabled", enabled)
                },
            ),
        )
        registerReadOnly(
            id = "state.agent_permission",
            title = "Agent 权限模式",
            category = "权限",
            summary = "读取当前完全访问、智能审批或手动审批模式",
        ) { AgentPermissionPolicy.mode().let { mode -> JSONObject().put("mode", mode.wireValue) } }
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.agent_permission",
                    title = "切换 Agent 权限模式",
                    category = "权限",
                    summary = "仅用户可切换审批方式；完全访问在具体授权任务内自动执行，系统和账号真实权限仍有效",
                    danger = EtaControlDanger.CONFIRM,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject().put(
                            "mode", JSONObject().put("type", "string")
                                .put("enum", JSONArray(AgentPermissionMode.entries.map { it.wireValue })),
                        ))
                        .put("required", JSONArray().put("mode"))
                        .put("additionalProperties", false),
                    canRead = true,
                ),
                read = { AgentPermissionPolicy.mode().let { mode -> JSONObject().put("mode", mode.wireValue) } },
                snapshot = { _, _ -> AgentPermissionPolicy.mode().let { mode -> JSONObject().put("mode", mode.wireValue) } },
                rollback = { _, before ->
                    val mode = AgentPermissionMode.entries.first { it.wireValue == before.getString("mode") }
                    AgentPermissionPolicy.setMode(mode)
                    JSONObject().put("mode", mode.wireValue).put("rolledBack", true)
                },
                execute = { _, parameters ->
                    val wire = parameters.getString("mode")
                    val mode = AgentPermissionMode.entries.firstOrNull { it.wireValue == wire }
                        ?: throw EtaControlException("INVALID_ARGUMENT", "未知的 Agent 权限模式")
                    AgentPermissionPolicy.setMode(mode)
                    JSONObject().put("mode", mode.wireValue).put("changed", true)
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.memory.clear",
                    title = "清空全局记忆",
                    category = "设置",
                    summary = "删除全局长期记忆文件；项目、会话和会议室作用域不受影响",
                    danger = EtaControlDanger.CONFIRM,
                    parameters = JSONObject(),
                    canRead = false,
                ),
                execute = { _, _ ->
                    val snapshot = AgentMemoryRepository.replaceAll("")
                    JSONObject().put("cleared", true).put("revision", snapshot.revision)
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.current_model",
                    title = "切换当前模型",
                    category = "模型",
                    summary = "选择已启用且属于指定提供商的模型作为当前默认模型",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("modelId", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 256))
                                .put("providerId", JSONObject().put("type", JSONArray().put("string").put("null"))),
                        )
                        .put("required", JSONArray().put("modelId"))
                        .put("additionalProperties", false),
                    canRead = true,
                ),
                read = { currentModelJson() },
                snapshot = { _, _ -> SettingsDataStore.settings().let {
                    JSONObject().put("providerId", it.selectedProviderId ?: JSONObject.NULL)
                        .put("modelId", it.selectedModelId ?: JSONObject.NULL)
                } },
                rollback = { _, before ->
                    if (before.isNull("modelId")) {
                        SettingsDataStore.setSelection(null, null)
                        return@Definition JSONObject().put("cleared", true)
                    }
                    val modelId = before.optString("modelId").trim()
                    require(modelId.isNotEmpty()) { "回滚快照缺少 modelId" }
                    ProviderRepository.ensureBuiltInsMerged()
                    val provider = ProviderRepository.providerByModelId(modelId)
                        ?.takeIf { it.isEnabled }
                        ?: throw EtaControlException("MODEL_NOT_FOUND", "原模型已不存在或不可用")
                    require(provider.models.any { it.id == modelId && it.isEnabled }) { "原模型已停用" }
                    SettingsDataStore.setSelection(provider.id, modelId)
                    currentModelJson().put("rolledBack", true)
                },
                execute = { _, parameters ->
                    val modelId = parameters.getString("modelId").trim()
                    val requestedProviderId = if (parameters.isNull("providerId")) null else {
                        parameters.optString("providerId").trim().takeIf(String::isNotEmpty)
                    }
                    ProviderRepository.ensureBuiltInsMerged()
                    val provider = ProviderRepository.providerByModelId(modelId)
                        ?.takeIf { it.isEnabled }
                        ?: throw EtaControlException("MODEL_NOT_FOUND", "模型不存在、已停用或不属于任何已启用提供商")
                    if (requestedProviderId != null && requestedProviderId != provider.id) {
                        throw EtaControlException("PROVIDER_MODEL_MISMATCH", "模型不属于指定提供商")
                    }
                    require(provider.models.any { it.id == modelId && it.isEnabled }) { "模型已停用" }
                    SettingsDataStore.setSelection(provider.id, modelId)
                    currentModelJson().put("selected", true)
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.appearance",
                    title = "外观与字体",
                    category = "设置",
                    summary = "修改皮肤、主题、配色、字体、模糊、手势和界面缩放，并支持完整回滚",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("skin", JSONObject().put("type", "string").put("enum", JSONArray(AppearanceSkin.entries.map { it.persistedValue })))
                                .put("themeMode", JSONObject().put("type", "string").put("enum", JSONArray(listOf("system", "light", "dark"))))
                                .put("monetEnabled", JSONObject().put("type", "boolean"))
                                .put("paletteStyle", JSONObject().put("type", "string"))
                                .put("accentColor", JSONObject().put("type", "string"))
                                .put("pureBlackEnabled", JSONObject().put("type", "boolean"))
                                .put("blurEnabled", JSONObject().put("type", "boolean"))
                                .put("topBarBlurStyle", JSONObject().put("type", "string"))
                                .put("swipeDismissEnabled", JSONObject().put("type", "boolean"))
                                .put("predictiveBackEnabled", JSONObject().put("type", "boolean"))
                                .put("interfaceScale", JSONObject().put("type", "number").put("minimum", 0.8).put("maximum", 1.1))
                                .put("fontFamily", JSONObject().put("type", "string").put("enum", JSONArray(listOf("system", "humanist", "editorial")))),
                        )
                        .put("additionalProperties", false),
                    canRead = true,
                ),
                read = { SettingsDataStore.settings().appearance.toControlJson() },
                rollback = { _, before ->
                    val appearance = before.toAppearanceSettings()
                    SettingsDataStore.setAppearanceSettings(appearance)
                    appearance.toControlJson().put("rolledBack", true)
                },
                execute = { _, parameters ->
                    require(parameters.length() > 0) { "至少指定一个外观设置" }
                    val current = SettingsDataStore.settings().appearance
                    val updated = current.copy(
                        skin = parameters.optString("skin").takeIf(String::isNotBlank)?.let {
                            AppearanceSkin.fromPersistedValue(it)
                        } ?: current.skin,
                        themeMode = parameters.optString("themeMode").takeIf(String::isNotBlank)?.let {
                            AppearanceThemeMode.fromPersistedValue(it)
                        } ?: current.themeMode,
                        monetEnabled = parameters.optBooleanOrNull("monetEnabled") ?: current.monetEnabled,
                        paletteStyle = parameters.optString("paletteStyle").takeIf(String::isNotBlank)?.let {
                            AppearancePaletteStyle.fromPersistedValue(it)
                        } ?: current.paletteStyle,
                        accentColor = parameters.optString("accentColor").takeIf(String::isNotBlank)?.let {
                            AppearanceAccentColor.fromPersistedValue(it)
                        } ?: current.accentColor,
                        pureBlackEnabled = parameters.optBooleanOrNull("pureBlackEnabled") ?: current.pureBlackEnabled,
                        blurEnabled = parameters.optBooleanOrNull("blurEnabled") ?: current.blurEnabled,
                        topBarBlurStyle = parameters.optString("topBarBlurStyle").takeIf(String::isNotBlank)?.let {
                            AppearanceTopBarBlurStyle.fromPersistedValue(it)
                        } ?: current.topBarBlurStyle,
                        swipeDismissEnabled = parameters.optBooleanOrNull("swipeDismissEnabled") ?: current.swipeDismissEnabled,
                        predictiveBackEnabled = parameters.optBooleanOrNull("predictiveBackEnabled") ?: current.predictiveBackEnabled,
                        interfaceScale = parameters.optDoubleOrNull("interfaceScale")?.toFloat()?.let(::normalizeInterfaceScale)
                            ?: current.interfaceScale,
                        fontFamily = parameters.optString("fontFamily").takeIf(String::isNotBlank)?.let {
                            AppearanceFontFamily.fromPersistedValue(it)
                        } ?: current.fontFamily,
                    ).normalized()
                    SettingsDataStore.setAppearanceSettings(updated)
                    updated.toControlJson()
                },
            ),
        )
        registerReadOnly(
            id = "state.model_roles",
            title = "模型角色",
            category = "模型",
            summary = "读取辅助、压缩、记忆、自学习和语音整理模型",
        ) { _ -> RuntimeConfigRepository.modelRoleSettings().toJson() }
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.model_roles",
                    title = "设置模型角色",
                    category = "模型",
                    summary = "设置全局辅助、压缩及各后台用途模型；未单独设置时继承辅助模型",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("auxiliaryProviderId", JSONObject().put("type", JSONArray().put("string").put("null")))
                                .put("auxiliaryModelId", JSONObject().put("type", JSONArray().put("string").put("null")))
                                .put("compactionProviderId", JSONObject().put("type", JSONArray().put("string").put("null")))
                                .put("compactionModelId", JSONObject().put("type", JSONArray().put("string").put("null")))
                                .put("memoryProviderId", JSONObject().put("type", JSONArray().put("string").put("null")))
                                .put("memoryModelId", JSONObject().put("type", JSONArray().put("string").put("null")))
                                .put("learningProviderId", JSONObject().put("type", JSONArray().put("string").put("null")))
                                .put("learningModelId", JSONObject().put("type", JSONArray().put("string").put("null")))
                                .put("transcriptProviderId", JSONObject().put("type", JSONArray().put("string").put("null")))
                                .put("transcriptModelId", JSONObject().put("type", JSONArray().put("string").put("null"))),
                        )
                        .put("additionalProperties", false),
                    canRead = true,
                ),
                read = { RuntimeConfigRepository.modelRoleSettings().toJson() },
                snapshot = { _, _ -> RuntimeConfigRepository.modelRoleSettings().toJson() },
                rollback = { _, before ->
                    val settings = before.toModelRoleSettings().normalized()
                    SettingsDataStore.setModelRoleSettings(settings)
                    settings.toJson().put("rolledBack", true)
                },
                execute = { _, parameters ->
                    fun id(name: String): String? = parameters.optionalModelRoleId(name)
                    val settings = ModelRoleSettings(
                        auxiliaryProviderId = id("auxiliaryProviderId"),
                        auxiliaryModelId = id("auxiliaryModelId"),
                        compactionProviderId = id("compactionProviderId"),
                        compactionModelId = id("compactionModelId"),
                        memoryProviderId = id("memoryProviderId"),
                        memoryModelId = id("memoryModelId"),
                        learningProviderId = id("learningProviderId"),
                        learningModelId = id("learningModelId"),
                        transcriptProviderId = id("transcriptProviderId"),
                        transcriptModelId = id("transcriptModelId"),
                    ).normalized()
                    ProviderRepository.ensureBuiltInsMerged()
                    val providers = ProviderRepository.allProviders().filter(ProviderSetting::hasUsableConnection)
                    settings.auxiliaryModelId?.let { modelId ->
                        requireNotNull(providers.enabledModel(modelId, settings.auxiliaryProviderId)) {
                            "辅助模型不存在、已停用或不属于指定提供商"
                        }
                    }
                    settings.compactionModelId?.let { modelId ->
                        requireNotNull(providers.enabledModel(modelId, settings.compactionProviderId)) {
                            "压缩模型不存在、已停用或不属于指定提供商"
                        }
                    }
                    settings.memoryModelId?.let { modelId ->
                        requireNotNull(providers.enabledModel(modelId, settings.memoryProviderId)) {
                            "记忆模型不存在、已停用或不属于指定提供商"
                        }
                    }
                    settings.learningModelId?.let { modelId ->
                        requireNotNull(providers.enabledModel(modelId, settings.learningProviderId)) {
                            "自学习模型不存在、已停用或不属于指定提供商"
                        }
                    }
                    settings.transcriptModelId?.let { modelId ->
                        requireNotNull(providers.enabledModel(modelId, settings.transcriptProviderId)) {
                            "语音整理模型不存在、已停用或不属于指定提供商"
                        }
                    }
                    SettingsDataStore.setModelRoleSettings(settings)
                    settings.toJson()
                },
            ),
        )
    }

    private fun registerReadOnlyState() {
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "state.current_model",
                    title = "当前模型",
                    category = "状态",
                    summary = "读取当前默认模型与工具支持状态，不暴露密钥",
                    danger = EtaControlDanger.SAFE,
                    parameters = JSONObject(),
                    canRead = true,
                ),
                read = {
                    val config = RuntimeConfigRepository.currentRuntimeConfig()
                        ?: throw EtaControlException("MODEL_NOT_CONFIGURED", "尚未配置可用模型")
                    JSONObject()
                        .put("providerId", config.providerId)
                        .put("providerName", config.providerName)
                        .put("modelId", config.model)
                        .put("modelName", config.modelDisplayName.ifBlank { config.model })
                        .put("supportsTools", config.supportsTools)
                        .put("contextWindow", config.contextWindow ?: JSONObject.NULL)
                },
                execute = { _, _ ->
                    throw EtaControlException("CAPABILITY_READ_ONLY", "当前模型状态只能读取")
                },
            ),
        )
    }

    private fun registerStructuredSettings() {
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun setting(id: String, title: String, read: suspend (Context) -> JSONObject, save: suspend (Context, JSONObject) -> Unit) {
            register(Definition(
                info = EtaControlCapabilityInfo(id, title, "设置", "读取或更新$title", EtaControlDanger.WRITE,
                    JSONObject().put("type", "object").put("properties", JSONObject().put("value", JSONObject().put("type", "object")))
                        .put("required", JSONArray().put("value")).put("additionalProperties", false), true),
                read = read, rollback = { context, before -> save(context, before); read(context) },
                execute = { context, parameters -> save(context, parameters.getJSONObject("value")); read(context) },
            ))
        }
        setting("settings.speech", "语音设置",
            read = { JSONObject(codec.encodeToString(SettingsDataStore.speechSettings())) },
            save = { _, value -> SettingsDataStore.setSpeechSettings(codec.decodeFromString<SpeechSettings>(value.toString())) })
        setting("settings.subagents", "子 Agent 设置",
            read = { JSONObject(codec.encodeToString(SettingsDataStore.subagentSettings())) },
            save = { _, value -> SettingsDataStore.setSubagentSettings(codec.decodeFromString<SubagentSettings>(value.toString())) })
        setting("settings.memory.automation", "记忆整理策略",
            read = { context -> JSONObject(codec.encodeToString(MemoryAutomationSettingsStore.read(context))) },
            save = { context, value -> MemoryAutomationSettingsStore.save(context, codec.decodeFromString<MemoryAutomationSettings>(value.toString())) })
        setting("settings.skill.evolution", "技能提炼设置",
            read = { context -> JSONObject().put("enabled", SkillEvolution.enabled(context))
                .put("dailyLimit", SkillEvolution.dailyLimit(context)).put("autoPublish", SkillEvolution.autoPublish(context)) },
            save = { context, value ->
                val limit = value.getInt("dailyLimit")
                require(limit in 1..30) { "SKILL_REVIEW_LIMIT_INVALID" }
                val enabled = value.getBoolean("enabled")
                val autoPublish = value.optBoolean("autoPublish", SkillEvolution.autoPublish(context))
                SkillEvolution.setDailyLimit(context, limit)
                SkillEvolution.setAutoPublish(context, autoPublish)
                SkillEvolution.setEnabled(context, enabled)
            })
    }

    private fun registerModelState() {
        registerReadOnly(
            id = "state.models",
            title = "模型目录",
            category = "模型",
            summary = "读取已配置提供商及模型能力，不暴露 API 密钥",
        ) { _ ->
            ProviderRepository.ensureBuiltInsMerged()
            val providers = ProviderRepository.allProviders()
            JSONObject().put(
                "providers",
                JSONArray().also { providerArray ->
                    providers.forEach { provider ->
                        providerArray.put(
                            JSONObject()
                                .put("id", provider.id)
                                .put("name", provider.name)
                                .put("enabled", provider.isEnabled)
                                .put("builtIn", provider.isBuiltIn)
                                .put("sourceType", provider.sourceType)
                                .put(
                                    "models",
                                    JSONArray().also { modelArray ->
                                        provider.models.sortedBy { it.sortOrder }.forEach { model ->
                                            modelArray.put(
                                                JSONObject()
                                                    .put("id", model.id)
                                                    .put("modelId", model.modelId)
                                                    .put("name", model.displayName)
                                                    .put("enabled", model.isEnabled)
                                                    .put("contextWindow", model.contextWindow ?: JSONObject.NULL)
                                                    .put("contextWindowOverride", model.contextWindowOverride ?: JSONObject.NULL)
                                                    .put("supportsTools", model.supportsTools)
                                                    .put("supportsVision", model.supportsVision)
                                                    .put("supportsReasoning", model.supportsReasoning),
                                            )
                                        }
                                    },
                                ),
                        )
                    }
                },
            )
        }
        registerReadOnly(
            id = "state.model_fallback",
            title = "备用模型",
            category = "模型",
            summary = "读取备用模型开关、提供商和模型",
        ) { _ ->
            RuntimeConfigRepository.modelFallbackSettings().toJson()
        }
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.model_fallback",
                    title = "设置备用模型",
                    category = "模型",
                    summary = "修改主模型临时失败时使用的备用提供商与模型",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("enabled", JSONObject().put("type", "boolean"))
                                .put(
                                    "providerId",
                                    JSONObject().put("type", JSONArray().put("string").put("null")),
                                )
                                .put(
                                    "modelId",
                                    JSONObject().put("type", JSONArray().put("string").put("null")),
                                ),
                        )
                        .put("required", JSONArray().put("enabled")),
                    canRead = true,
                ),
                read = { RuntimeConfigRepository.modelFallbackSettings().toJson() },
                rollback = { _, before ->
                    val settings = ModelFallbackSettings(
                        enabled = before.getBoolean("enabled"),
                        modelId = before.optString("modelId").trim().takeIf(String::isNotEmpty),
                        providerId = before.optString("providerId").trim().takeIf(String::isNotEmpty),
                    ).normalized()
                    SettingsDataStore.setModelFallbackSettings(settings)
                    settings.toJson().put("rolledBack", true)
                },
                execute = { _, parameters ->
                    require(parameters.has("enabled")) { "缺少 enabled" }
                    val enabled = parameters.getBoolean("enabled")
                    val modelId = parameters.optString("modelId").trim().takeIf(String::isNotEmpty)
                    val providerId = parameters.optString("providerId").trim().takeIf(String::isNotEmpty)
                    if (enabled) {
                        requireNotNull(modelId) { "启用备用模型时必须指定 modelId" }
                        ProviderRepository.ensureBuiltInsMerged()
                        requireNotNull(ProviderRepository.allProviders().enabledModel(modelId, providerId)) {
                            "备用模型不存在、未启用或不属于指定提供商"
                        }
                    }
                    val settings = ModelFallbackSettings(
                        enabled = enabled,
                        modelId = modelId,
                        providerId = providerId,
                    ).normalized()
                    SettingsDataStore.setModelFallbackSettings(settings)
                    settings.toJson()
                },
            ),
        )
        registerReadOnly(
            id = "state.model_candidates",
            title = "候选模型集合",
            category = "模型",
            summary = "读取当前主模型的候选集合、用途与能力约束",
        ) { _ ->
            RuntimeConfigRepository.modelCandidateSettings().toJson()
        }
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.model_candidates",
                    title = "设置候选模型集合",
                    category = "模型",
                    summary = "持久化主模型之后仅在当前可用候选中路由的模型绑定",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("enabled", JSONObject().put("type", "boolean"))
                                .put("candidates", JSONObject()
                                    .put("type", "array")
                                    .put("maxItems", ModelCandidateSettings.MAX_CANDIDATES)
                                    .put("items", JSONObject()
                                        .put("type", "object")
                                        .put("properties", JSONObject()
                                            .put("providerId", JSONObject().put("type", "string"))
                                            .put("modelId", JSONObject().put("type", "string"))
                                            .put("enabled", JSONObject().put("type", "boolean"))
                                            .put("purpose", JSONObject().put("type", "string"))
                                            .put("requiredTools", JSONObject().put("type", "boolean"))
                                            .put("requiredVision", JSONObject().put("type", "boolean"))
                                            .put("minimumContextWindow", JSONObject()
                                                .put("type", JSONArray().put("integer").put("null")))
                                            .put("priority", JSONObject().put("type", "integer")))
                                        .put("required", JSONArray().put("providerId").put("modelId")))
                                .put("required", JSONArray().put("enabled")))),
                    canRead = true,
                ),
                read = { RuntimeConfigRepository.modelCandidateSettings().toJson() },
                rollback = { _, before ->
                    val settings = before.toModelCandidateSettings()
                    SettingsDataStore.setModelCandidateSettings(settings)
                    settings.toJson().put("rolledBack", true)
                },
                execute = { _, parameters ->
                    require(parameters.has("enabled")) { "缺少 enabled" }
                    val settings = parameters.toModelCandidateSettings()
                    SettingsDataStore.setModelCandidateSettings(settings)
                    settings.toJson()
                },
            ),
        )
    }

    private fun registerLocalModels() {
        registerReadOnly(
            id = "state.local_models",
            title = "本地模型",
            category = "模型",
            summary = "读取本地文本、视觉、语音与向量模型的安装、绑定和运行状态",
        ) { context ->
            LocalModelRepository.init(context)
            val runtime = LocalModelRepository.runtime.value
            val download = LocalModelRepository.download.value
            JSONObject()
                .put(
                    "models",
                    JSONArray().also { array ->
                        LocalModelRepository.models.value.forEach { model ->
                            array.put(
                                JSONObject()
                                    .put("id", model.id)
                                    .put("name", model.displayName)
                                    .put("format", model.format.name)
                                    .put("runtime", model.runtime.name)
                                    .put("sizeBytes", model.sizeBytes)
                                    .put("inputModalities", JSONArray(model.inputModalities))
                                    .put("outputModalities", JSONArray(model.outputModalities))
                                    .put("supportsTools", model.supportsTools)
                                    .put("supportsVision", model.supportsVision)
                                    .put("boundProviderId", model.boundProviderId ?: JSONObject.NULL)
                                    .put("boundModelSelectionId", model.boundModelSelectionId ?: JSONObject.NULL)
                                    .put("contextWindow", model.contextWindow)
                                    .put("backend", model.runtimeSettings.backend.name),
                            )
                        }
                    },
                )
                .put(
                    "runtime",
                    JSONObject()
                        .put("recordId", runtime.recordId ?: JSONObject.NULL)
                        .put("state", runtime.state.name)
                        .put("backend", runtime.backend)
                        .put("message", runtime.message),
                )
                .put(
                    "download",
                    JSONObject()
                        .put("id", download.id ?: JSONObject.NULL)
                        .put("name", download.displayName)
                        .put("stage", download.stage?.name ?: JSONObject.NULL)
                        .put("bytesDownloaded", download.bytesDownloaded)
                        .put("totalBytes", download.totalBytes ?: JSONObject.NULL),
                )
                .put("textScheduling", io.github.mangi.eta.agent.runtime.LocalInferenceScheduling.snapshot())
                .put("embeddingRuntime", io.github.mangi.eta.agent.model.LocalEmbeddingRuntime.state.value.json())
                .put("wakeWord", io.github.mangi.eta.agent.voice.BackgroundWakeWordRuntime.state.value.let {
                    JSONObject().put("phase", it.phase.name).put("message", it.message)
                })
                .put("speechRuntime", JSONArray(io.github.mangi.eta.agent.voice.LocalSpeechRuntime.states.value.values.map { speech ->
                    JSONObject().put("kind", speech.kind.name).put("recordId", speech.recordId ?: JSONObject.NULL)
                        .put("phase", speech.phase.name).put("requestedBackend", speech.requestedBackend)
                        .put("activeBackend", speech.activeBackend).put("updatedAt", speech.updatedAt)
                        .put("metrics", speech.metrics?.json() ?: JSONObject.NULL)
                }))
        }
    }

    private fun registerMcpState() {
        registerReadOnly(
            id = "state.mcp_servers",
            title = "MCP 服务",
            category = "智能",
            summary = "读取 MCP 服务、启用状态、工具目录与授权状态，不暴露凭据",
        ) { context ->
            McpServerRepository.init(context)
            JSONObject().put(
                "servers",
                JSONArray().also { array ->
                    McpServerRepository.servers().forEach { server ->
                        array.put(
                            JSONObject()
                                .put("id", server.id)
                                .put("name", server.name)
                                .put("enabled", server.enabled)
                                .put("protocolMode", server.protocolMode)
                                .put("authorizationType", server.authorizationType)
                                .put("activeToolCount", server.activeTools.size)
                .put("toolCount", server.tools.size)
                                .put(
                                    "tools",
                                    JSONArray(server.tools.map { tool ->
                                        JSONObject()
                                            .put("name", tool.name)
                                            .put("title", tool.title)
                                            .put("enabled", tool.name in server.enabledToolNames)
                                            .put("readOnly", tool.readOnlyHint ?: JSONObject.NULL)
                                            .put("destructive", tool.destructiveHint ?: JSONObject.NULL)
                                    }),
                                ),
                        )
                    }
                },
            )
        }
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.mcp_server",
                    title = "MCP 状态与运行位置",
                    category = "智能",
                    summary = "修改 MCP 服务启用状态和本地运行环境；不会读取或修改认证凭据",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("serverId", JSONObject().put("type", "string").put("minLength", 1))
                                .put("enabled", JSONObject().put("type", "boolean"))
                                .put("executionTarget", JSONObject().put("type", "string").put("enum", JSONArray(listOf("android", "debian", "alpine"))))
                                .put("linuxBackend", JSONObject().put("type", "string").put("enum", JSONArray(listOf("chroot", "proot")))),
                        )
                        .put("required", JSONArray(listOf("serverId")))
                        .put("additionalProperties", false),
                    canRead = false,
                ),
                snapshot = { context, parameters ->
                    McpServerRepository.init(context)
                    val server = McpServerRepository.serverById(parameters.optString("serverId"))
                        ?: throw EtaControlException("MCP_SERVER_NOT_FOUND", "MCP 服务不存在")
                    JSONObject().put("serverId", server.id).put("enabled", server.enabled)
                        .put("executionTarget", server.executionTarget).put("linuxBackend", server.linuxBackend)
                },
                rollback = { context, before ->
                    McpServerRepository.init(context)
                    val server = McpServerRepository.serverById(before.getString("serverId"))
                        ?: throw EtaControlException("MCP_SERVER_NOT_FOUND", "MCP 服务已不存在，无法回滚")
                    val enabled = before.getBoolean("enabled")
                    val target = before.optString("executionTarget", "android")
                    val backend = before.optString("linuxBackend", "chroot")
                    check(io.github.mangi.eta.agent.mcp.McpServerManager.stopLocal(server.id)) {
                        "MCP 本地服务未能停止，已保留当前设置"
                    }
                    McpServerRepository.update(server, server.copy(enabled = enabled, executionTarget = target, linuxBackend = backend,
                        lastProtocolVersion = null, toolsExpireAt = null))
                    JSONObject().put("serverId", server.id).put("enabled", enabled).put("executionTarget", target)
                        .put("linuxBackend", backend).put("rolledBack", true)
                },
                execute = { context, parameters ->
                    McpServerRepository.init(context)
                    val server = McpServerRepository.serverById(parameters.getString("serverId"))
                        ?: throw EtaControlException("MCP_SERVER_NOT_FOUND", "MCP 服务不存在")
                    require(parameters.has("enabled") || parameters.has("executionTarget") || parameters.has("linuxBackend")) { "请提供需要修改的 MCP 设置" }
                    val enabled = if (parameters.has("enabled")) parameters.getBoolean("enabled") else server.enabled
                    val target = parameters.optString("executionTarget", server.executionTarget)
                    val backend = parameters.optString("linuxBackend", server.linuxBackend)
                    require(target in setOf("android", "debian", "alpine") && backend in setOf("chroot", "proot")) { "MCP 运行位置无效" }
                    val changed = target != server.executionTarget || backend != server.linuxBackend
                    require(!changed || server.transportType == io.github.mangi.eta.data.model.McpTransportType.STDIO || server.launchCommand.isNotBlank()) { "远程 MCP 不需要配置本地运行位置" }
                    if (!enabled || changed) check(io.github.mangi.eta.agent.mcp.McpServerManager.stopLocal(server.id)) {
                        "MCP 本地服务未能停止，未保存设置"
                    }
                    McpServerRepository.update(server, server.copy(enabled = enabled, executionTarget = target, linuxBackend = backend,
                        lastProtocolVersion = if (changed) null else server.lastProtocolVersion,
                        toolsExpireAt = if (changed) null else server.toolsExpireAt))
                    JSONObject().put("serverId", server.id).put("enabled", enabled).put("executionTarget", target).put("linuxBackend", backend)
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.mcp_server.refresh",
                    title = "刷新 MCP 工具目录",
                    category = "智能",
                    summary = "连接已有 MCP 服务并更新实际发现的工具，不读取或返回认证凭据",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("serverId", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 256)))
                        .put("required", JSONArray().put("serverId")).put("additionalProperties", false),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    McpServerRepository.init(context)
                    val server = io.github.mangi.eta.agent.mcp.McpServerManager.refresh(parameters.getString("serverId"))
                    JSONObject().put("serverId", server.id).put("toolCount", server.tools.size)
                        .put("enabledToolCount", server.activeTools.size)
                        .put("tools", JSONArray(server.tools.map { tool -> JSONObject()
                            .put("name", tool.name).put("title", tool.title)
                            .put("enabled", tool.name in server.enabledToolNames) }))
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.mcp_tool",
                    title = "授权或停用 MCP 工具",
                    category = "智能",
                    summary = "只允许切换该 MCP 服务已发现的工具，不允许借此新增外部服务",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("serverId", JSONObject().put("type", "string").put("minLength", 1))
                                .put("toolName", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 256))
                                .put("enabled", JSONObject().put("type", "boolean")),
                        )
                        .put("required", JSONArray(listOf("serverId", "toolName", "enabled")))
                        .put("additionalProperties", false),
                    canRead = false,
                ),
                snapshot = { context, parameters ->
                    McpServerRepository.init(context)
                    val serverId = parameters.optString("serverId")
                    val toolName = parameters.optString("toolName")
                    val server = McpServerRepository.serverById(serverId)
                        ?: throw EtaControlException("MCP_SERVER_NOT_FOUND", "MCP 服务不存在")
                    if (server.tools.none { it.name == toolName }) {
                        throw EtaControlException("MCP_TOOL_NOT_FOUND", "该 MCP 服务未发现指定工具")
                    }
                    JSONObject().put("serverId", serverId).put("toolName", toolName)
                        .put("enabled", toolName in server.enabledToolNames)
                },
                rollback = { context, before ->
                    McpServerRepository.init(context)
                    val serverId = before.getString("serverId")
                    val toolName = before.getString("toolName")
                    val enabled = before.getBoolean("enabled")
                    McpServerRepository.setToolEnabled(serverId, toolName, enabled)
                    JSONObject().put("serverId", serverId).put("toolName", toolName)
                        .put("enabled", enabled).put("rolledBack", true)
                },
                execute = { context, parameters ->
                    McpServerRepository.init(context)
                    val serverId = parameters.getString("serverId")
                    val toolName = parameters.getString("toolName")
                    val server = McpServerRepository.serverById(serverId)
                        ?: throw EtaControlException("MCP_SERVER_NOT_FOUND", "MCP 服务不存在")
                    if (server.tools.none { it.name == toolName }) {
                        throw EtaControlException("MCP_TOOL_NOT_FOUND", "该 MCP 服务未发现指定工具")
                    }
                    val enabled = parameters.getBoolean("enabled")
                    McpServerRepository.setToolEnabled(serverId, toolName, enabled)
                    JSONObject().put("serverId", serverId).put("toolName", toolName).put("enabled", enabled)
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "mcp_server.delete",
                    title = "删除 MCP 服务",
                    category = "智能",
                    summary = "删除 MCP 配置及其加密凭据；此操作不可撤销",
                    danger = EtaControlDanger.CONFIRM,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("serverId", JSONObject().put("type", "string").put("minLength", 1)),
                        )
                        .put("required", JSONArray().put("serverId"))
                        .put("additionalProperties", false),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    McpServerRepository.init(context)
                    val serverId = parameters.getString("serverId")
                    McpServerLifecycleService().delete(serverId)
                    JSONObject().put("serverId", serverId).put("deleted", true)
                },
            ),
        )
    }

    private fun registerApiConnectorState() {
        registerReadOnly(
            id = "state.api_connectors",
            title = "REST API 连接",
            category = "智能",
            summary = "读取 API 连接和已发现操作的启用状态，不返回地址、认证配置或凭据",
        ) { context ->
            val connectors = io.github.mangi.eta.agent.capability.ApiConnectorStore(context).load()
            JSONObject().put("connectors", JSONArray(connectors.map { connector ->
                JSONObject().put("id", connector.id).put("name", connector.name)
                    .put("enabled", connector.enabled)
                    .put("binding", ApiConnectorSettingsControlProvider.snapshot(connector).getString("binding"))
                    .put("actions", JSONArray(connector.actions.map { action ->
                        JSONObject().put("name", action.name).put("method", action.method)
                            .put("enabled", action.enabled).put("destructive", action.destructive)
                            .put("binding", ApiConnectorSettingsControlProvider.snapshot(connector, action.name).getString("binding"))
                    }))
            }))
        }
    }

    private fun registerSkillState() {
        registerReadOnly(
            id = "state.skills",
            title = "Skill 目录",
            category = "智能",
            summary = "读取已安装 Skill、启用状态、来源、能力目录和兼容信息",
        ) { context ->
            val entries = SkillRuntime.createIndexService(context)
                .listSkillsForManagement(forceRefresh = true)
                .filter { it.installed }
            JSONObject().put(
                "skills",
                JSONArray(entries.map { entry ->
                    JSONObject()
                        .put("id", entry.id)
                        .put("name", entry.name)
                        .put("description", entry.description)
                        .put("enabled", entry.enabled)
                        .put("source", entry.source)
                        .put("hasScripts", entry.hasScripts)
                        .put("hasReferences", entry.hasReferences)
                        .put("hasAssets", entry.hasAssets)
                        .put("hasEvals", entry.hasEvals)
                        .put("compatibility", entry.compatibility ?: JSONObject.NULL)
                }),
            )
        }
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.skill.enabled",
                    title = "启用或停用 Skill",
                    category = "智能",
                    summary = "切换已安装 Skill 是否可被 Agent 发现和加载",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("skillId", JSONObject().put("type", "string").put("minLength", 1))
                                .put("enabled", JSONObject().put("type", "boolean")),
                        )
                        .put("required", JSONArray(listOf("skillId", "enabled")))
                        .put("additionalProperties", false),
                    canRead = false,
                ),
                snapshot = { context, parameters ->
                    val skillId = parameters.optString("skillId")
                    val entry = SkillRuntime.createIndexService(context)
                        .listSkillsForManagement(forceRefresh = true)
                        .firstOrNull { it.installed && it.id == skillId }
                        ?: throw EtaControlException("SKILL_NOT_FOUND", "Skill 未安装或不存在")
                    JSONObject().put("skillId", entry.id).put("enabled", entry.enabled)
                },
                rollback = { context, before ->
                    val skillId = before.getString("skillId")
                    val enabled = before.getBoolean("enabled")
                    val entry = SkillRuntime.createIndexService(context).setSkillEnabled(skillId, enabled)
                    JSONObject().put("skillId", entry.id).put("enabled", entry.enabled).put("rolledBack", true)
                },
                execute = { context, parameters ->
                    val skillId = parameters.getString("skillId")
                    val enabled = parameters.getBoolean("enabled")
                    val entry = SkillRuntime.createIndexService(context).setSkillEnabled(skillId, enabled)
                    JSONObject().put("skillId", entry.id).put("enabled", entry.enabled)
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "skill.read_document",
                    title = "读取 Skill 正文",
                    category = "智能",
                    summary = "读取已安装 Skill 的名称、正文和修订标识，供软件管家编辑前核对",
                    danger = EtaControlDanger.SAFE,
                    parameters = JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("skillId", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 256)))
                        .put("required", JSONArray().put("skillId")).put("additionalProperties", false),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    val skillId = parameters.getString("skillId")
                    val document = SkillRuntime.createIndexService(context).readForEdit(skillId)
                    JSONObject().put("skillId", skillId).put("name", document.name)
                        .put("body", document.body).put("revision", document.revision)
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.skill.document",
                    title = "编辑 Skill 名称与正文",
                    category = "智能",
                    summary = "保存已安装 Skill 的显示名称和正文，保留其他元数据和资源；修改前必须读取当前修订",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()
                        .put("skillId", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 256))
                        .put("name", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 100))
                        .put("body", JSONObject().put("type", "string").put("maxLength", 524288))
                        .put("revision", JSONObject().put("type", "string").put("minLength", 64).put("maxLength", 64)))
                        .put("required", JSONArray(listOf("skillId", "name", "body", "revision")))
                        .put("additionalProperties", false),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    val skillId = parameters.getString("skillId")
                    val service = SkillRuntime.createIndexService(context)
                    service.saveEdit(skillId, parameters.getString("name"), parameters.getString("body"), parameters.getString("revision"))
                    val saved = service.readForEdit(skillId)
                    JSONObject().put("skillId", skillId).put("name", saved.name).put("revision", saved.revision)
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "skill.delete",
                    title = "删除 Skill",
                    category = "智能",
                    summary = "删除已安装 Skill 及其本地文件；内置 Skill 会恢复为卸载状态，不删除内置资产",
                    danger = EtaControlDanger.CONFIRM,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("skillId", JSONObject().put("type", "string").put("minLength", 1)),
                        )
                        .put("required", JSONArray().put("skillId"))
                        .put("additionalProperties", false),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    val skillId = parameters.getString("skillId")
                    if (!SkillRuntime.createIndexService(context).deleteSkill(skillId)) {
                        throw EtaControlException("SKILL_NOT_FOUND", "Skill 不存在或无法安全删除")
                    }
                    JSONObject().put("skillId", skillId).put("deleted", true)
                },
            ),
        )
    }

    private fun registerPromptState() {
        registerReadOnly(
            id = "state.prompt_templates",
            title = "提示词模板",
            category = "智能",
            summary = "读取提示词模板、作用位置及各会话范围绑定",
        ) { _ ->
            JSONObject()
                .put(
                    "templates",
                    JSONArray(PromptInjectionStore.templates().map { template ->
                        JSONObject()
                            .put("id", template.id)
                            .put("name", template.name)
                            .put("description", template.description)
                            .put("enabled", template.enabled)
                            .put("source", template.source)
                            .put("characterCount", template.characterCount)
                            .put(
                                "blocks",
                                JSONArray(template.blocks.sortedWith(io.github.mangi.eta.agent.prompt.PromptTemplate.BLOCK_ORDER).map {
                                    JSONObject()
                                        .put("title", it.title)
                                        .put("position", it.position.wireValue)
                                        .put("enabled", it.enabled)
                                }),
                            )
                    }),
                )
                .put(
                    "bindings",
                    JSONArray(PromptInjectionStore.bindings().map { binding ->
                        binding.toJson()
                    }),
                )
        }
    }

    private fun registerSubagentState() {
        registerReadOnly(
            id = "state.subagents",
            title = "子 Agent",
            category = "智能",
            summary = "读取内置与自定义子 Agent、角色指令和工具边界",
        ) { _ ->
            JSONObject().put(
                "subagents",
                JSONArray(SubagentProfileCatalog.listAll().take(100).map { profile ->
                    JSONObject()
                        .put("name", profile.name)
                        .put("summary", profile.summary)
                        .put("whenToUse", profile.whenToUse)
                        .put("builtin", profile.builtin)
                        .put("override", profile.override)
                        .put("toolHighlights", JSONArray(profile.toolHighlights))
                        .put(
                            "explicitTools",
                            profile.explicitTools?.let { JSONArray(it.toList()) } ?: JSONObject.NULL,
                        )
                        .put("disallowedTools", JSONArray(profile.disallowedTools.toList()))
                        .put("roleInstruction", profile.roleInstruction.take(4_000))
                }),
            )
        }
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "subagent.create",
                    title = "创建子 Agent",
                    category = "智能",
                    summary = "创建或更新自定义子 Agent 的角色指令、使用条件和工具边界；工具边界不会提升运行时权限",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string").put("minLength", 2).put("maxLength", 48))
                                .put("description", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 600))
                                .put("prompt", JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 32_000))
                                .put("whenToUse", JSONObject().put("type", "string").put("maxLength", 2_000))
                                .put("tools", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                                .put("disallowedTools", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                                .put("overrideBuiltin", JSONObject().put("type", "boolean")),
                        )
                        .put("required", JSONArray(listOf("name", "description", "prompt")))
                        .put("additionalProperties", false),
                    canRead = false,
                ),
                execute = { _, parameters ->
                    withContext(Dispatchers.IO) {
                        val profile = SubagentProfileCatalog.saveCustomProfile(
                            name = parameters.getString("name"),
                            description = parameters.getString("description"),
                            prompt = parameters.getString("prompt"),
                            whenToUse = parameters.optString("whenToUse"),
                            tools = parameters.optJSONArray("tools").toStringList(),
                            disallowedTools = parameters.optJSONArray("disallowedTools").toStringList(),
                            overrideBuiltin = parameters.optBoolean("overrideBuiltin", false),
                        )
                        JSONObject()
                            .put("name", profile.name)
                            .put("summary", profile.summary)
                            .put("builtin", profile.builtin)
                    }
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "subagent.delete",
                    title = "删除自定义子 Agent",
                    category = "智能",
                    summary = "删除 agents 工作区中的自定义子 Agent；内置子 Agent 不会删除",
                    danger = EtaControlDanger.CONFIRM,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("name", JSONObject().put("type", "string").put("minLength", 2).put("maxLength", 48)),
                        )
                        .put("required", JSONArray().put("name"))
                        .put("additionalProperties", false),
                    canRead = false,
                ),
                execute = { _, parameters ->
                    val name = parameters.getString("name")
                    val deleted = withContext(Dispatchers.IO) {
                        SubagentProfileCatalog.deleteCustomProfile(name)
                    }
                    if (!deleted) throw EtaControlException("SUBAGENT_NOT_FOUND", "自定义子 Agent 不存在或无法安全删除")
                    JSONObject().put("name", name.trim().lowercase()).put("deleted", true)
                },
            ),
        )
    }

    private fun registerProjectState() {
        registerReadOnly(
            id = "state.projects",
            title = "项目文件夹",
            category = "文件",
            summary = "读取项目名称、受管目录、归档状态及会话或会议室绑定",
        ) { context ->
            val projects = ProjectRepository.projects(context).first()
            JSONObject().put(
                "projects",
                JSONArray().also { array ->
                    projects.forEach { project ->
                        array.put(
                            JSONObject()
                                .put("id", project.id)
                                .put("name", project.name)
                                .put("providerId", project.providerId)
                                .put("rootDisplayName", project.rootDisplayName)
                                .put("source", project.source)
                                .put("archived", project.archivedAt != null)
                                .put("updatedAt", project.updatedAt),
                        )
                    }
                },
            )
        }
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "project.create",
                    title = "创建项目",
                    category = "文件",
                    summary = "把已获准访问的目录登记为项目专属文件夹",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string"))
                                .put("providerId", JSONObject().put("type", "string"))
                                .put("rootIdentityKey", JSONObject().put("type", "string"))
                                .put("rootDisplayName", JSONObject().put("type", "string"))
                                .put("source", JSONObject().put("type", "string")),
                        )
                        .put(
                            "required",
                            JSONArray(listOf("name", "providerId", "rootIdentityKey", "rootDisplayName", "source")),
                        ),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    val id = ProjectRepository.create(
                        context,
                        ProjectDraft(
                            name = parameters.getString("name"),
                            providerId = parameters.getString("providerId"),
                            rootIdentityKey = parameters.getString("rootIdentityKey"),
                            rootDisplayName = parameters.getString("rootDisplayName"),
                            source = parameters.getString("source"),
                        ),
                    )
                    JSONObject().put("projectId", id)
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "project.rename",
                    title = "重命名项目",
                    category = "文件",
                    summary = "修改项目显示名称，不移动项目目录",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("projectId", JSONObject().put("type", "string"))
                                .put("name", JSONObject().put("type", "string")),
                        )
                        .put("required", JSONArray(listOf("projectId", "name"))),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    val projectId = parameters.getString("projectId")
                    val name = parameters.getString("name")
                    ProjectRepository.rename(context, projectId, name)
                    JSONObject().put("projectId", projectId).put("name", name.trim())
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "project.archive",
                    title = "归档项目",
                    category = "文件",
                    summary = "归档或恢复项目及其会话上下文入口，不删除项目文件",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("projectId", JSONObject().put("type", "string"))
                                .put("archived", JSONObject().put("type", "boolean")),
                        )
                        .put("required", JSONArray(listOf("projectId", "archived"))),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    val projectId = parameters.getString("projectId")
                    val archived = parameters.getBoolean("archived")
                    ProjectRepository.setArchived(context, projectId, archived)
                    JSONObject().put("projectId", projectId).put("archived", archived)
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "project.delete",
                    title = "移除项目",
                    category = "文件",
                    summary = "从 Nexus 移除项目登记及其会话、会议室绑定；不会删除磁盘上的原始文件夹",
                    danger = EtaControlDanger.CONFIRM,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("projectId", JSONObject().put("type", "string")),
                        )
                        .put("required", JSONArray().put("projectId")),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    val projectId = parameters.getString("projectId")
                    ProjectRepository.delete(context, projectId)
                    JSONObject().put("projectId", projectId).put("registered", false)
                },
            ),
        )
    }

    private fun registerLearningState() {
        registerReadOnly(
            id = "state.memory_candidates",
            title = "记忆候选",
            category = "智能",
            summary = "读取等待软件管家复核或等待人工批准的记忆候选",
        ) { context ->
            val entries = LearningDatabase.get(context).memoryDao().entries()
            JSONObject().put(
                "candidates",
                JSONArray(entries
                    .filter { it.status in setOf("butler_review", "butler_approved", "pending", "conflict") }
                    .take(200)
                    .map { entry ->
                        JSONObject()
                            .put("id", entry.id)
                            .put("scope", entry.scope)
                            .put("layer", entry.layer)
                            .put("topic", entry.topic)
                            .put("status", entry.status)
                            .put("confidence", entry.confidence)
                            .put("conflictIds", entry.conflictIds)
                            .put("updatedAt", entry.updatedAt)
                    }),
            )
        }
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "memory.candidate.review",
                    title = "审核记忆候选",
                    category = "智能",
                    summary = "软件管家复核内容质量、证据与冲突；通过后才允许人工批准",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("candidateId", JSONObject().put("type", "string").put("minLength", 1)),
                        )
                        .put("required", JSONArray().put("candidateId"))
                        .put("additionalProperties", false),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    val db = LearningDatabase.get(context)
                    val candidateId = parameters.getString("candidateId")
                    val entry = db.memoryDao().entry(candidateId)
                        ?: throw EtaControlException("MEMORY_CANDIDATE_NOT_FOUND", "记忆候选不存在")
                    val rejection = MemoryReviewRepository(db).review(candidateId, entry.updatedAt)
                    if (rejection != null) throw EtaControlException("MEMORY_BUTLER_REJECTED", rejection)
                    JSONObject().put("candidateId", candidateId).put("status", "butler_approved")
                },
            ),
        )
        registerReadOnly(
            id = "state.learning",
            title = "自学习状态",
            category = "智能",
            summary = "读取自学习开关、候选规则、生效规则和冲突状态",
        ) { context ->
            val snapshot = LearningRepository.get(context).snapshot()
            JSONObject()
                .put("enabled", LearningSettingsStore.isEnabled(context))
                .put("automaticCaptureEnabled", LearningSettingsStore.automaticCaptureEnabled(context))
                .put("automaticPromotionEnabled", LearningSettingsStore.automaticPromotionEnabled(context))
                .put("candidateCount", snapshot.candidates.size)
                .put("ruleCount", snapshot.rules.size)
                .put(
                    "candidates",
                    JSONArray(snapshot.candidates.take(100).map { candidate ->
                        JSONObject()
                            .put("id", candidate.id)
                            .put("kind", candidate.kind.name)
                            .put("title", candidate.title)
                            .put("status", candidate.status.name)
                            .put("confidence", candidate.confidence)
                            .put("conflictCount", candidate.conflicts.size)
                                            .put("butlerReview", candidate.reviewSummary)
                    }),
                )
                .put(
                    "rules",
                    JSONArray(snapshot.rules.take(100).map { rule ->
                        JSONObject()
                            .put("id", rule.id)
                            .put("kind", rule.kind.name)
                            .put("title", rule.title)
                            .put("active", rule.active)
                            .put("pinned", rule.pinned)
                            .put("version", rule.currentVersion)
                            .put(
                                "versions",
                                JSONArray(rule.versions.takeLast(20).map { version ->
                                    JSONObject()
                                        .put("id", version.id)
                                        .put("version", version.version)
                                        .put("reason", version.reason)
                                        .put("createdAt", version.createdAt)
                                }),
                            )
                    }),
                )
        }
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.learning",
                    title = "自学习设置",
                    category = "智能",
                    summary = "启用或停用自学习与自动经验采集",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("enabled", JSONObject().put("type", "boolean"))
                                .put("automaticCaptureEnabled", JSONObject().put("type", "boolean"))
                                .put("automaticPromotionEnabled", JSONObject().put("type", "boolean")),
                        )
                        .put("additionalProperties", false),
                    canRead = true,
                ),
                read = { context -> learningSettingsJson(context) },
                rollback = { context, before ->
                    if (before.has("enabled")) {
                        LearningSettingsStore.setEnabled(context, before.getBoolean("enabled"))
                    }
                    if (before.has("automaticCaptureEnabled")) {
                        LearningSettingsStore.setAutomaticCaptureEnabled(
                            context,
                            before.getBoolean("automaticCaptureEnabled"),
                        )
                    }
                    if (before.has("automaticPromotionEnabled")) {
                        LearningSettingsStore.setAutomaticPromotionEnabled(
                            context,
                            before.getBoolean("automaticPromotionEnabled"),
                        )
                    }
                    learningSettingsJson(context).put("rolledBack", true)
                },
                execute = { context, parameters ->
                    if (!parameters.has("enabled") && !parameters.has("automaticCaptureEnabled") &&
                        !parameters.has("automaticPromotionEnabled")) {
                        throw EtaControlException("INVALID_PARAMETERS", "至少指定一个自学习开关")
                    }
                    parameters.optBooleanOrNull("enabled")?.let { LearningSettingsStore.setEnabled(context, it) }
                    parameters.optBooleanOrNull("automaticCaptureEnabled")?.let {
                        LearningSettingsStore.setAutomaticCaptureEnabled(context, it)
                    }
                    parameters.optBooleanOrNull("automaticPromotionEnabled")?.let {
                        LearningSettingsStore.setAutomaticPromotionEnabled(context, it)
                    }
                    learningSettingsJson(context)
                },
            ),
        )
        registerLearningMutation(
            id = "learning.candidate.review",
            title = "审核学习候选",
            summary = "由软件管家复核证据、具体性和可执行性；通过的候选才允许进入人工批准",
            danger = EtaControlDanger.WRITE,
        ) { context, candidateId ->
            LearningRepository.get(context).reviewCandidate(candidateId)
        }
        registerLearningMutation(
            id = "learning.candidate.promote",
            title = "晋升学习候选",
            summary = "把已审核的学习候选写入稳定规则；操作可在学习页回滚",
        ) { context, candidateId ->
            LearningRepository.get(context).promote(candidateId)
        }
        registerLearningMutation(
            id = "learning.candidate.reject",
            title = "拒绝学习候选",
            summary = "拒绝候选规则，后续自动学习不会再次直接采用相同候选",
        ) { context, candidateId ->
            LearningRepository.get(context).reject(candidateId)
        }
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "learning.rule.rollback",
                    title = "回滚学习规则",
                    category = "智能",
                    summary = "把自学习规则恢复到指定历史版本；回滚本身会生成新的版本",
                    danger = EtaControlDanger.CONFIRM,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("ruleId", JSONObject().put("type", "string").put("minLength", 1))
                                .put("versionId", JSONObject().put("type", "string").put("minLength", 1)),
                        )
                        .put("required", JSONArray(listOf("ruleId", "versionId")))
                        .put("additionalProperties", false),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    learningMutationResultJson(
                        LearningRepository.get(context).rollback(
                            parameters.getString("ruleId"),
                            parameters.getString("versionId"),
                        ),
                    )
                },
            ),
        )
    }

    private fun registerBackupState() {
        registerReadOnly(
            id = "state.backup",
            title = "备份状态",
            category = "系统",
            summary = "读取自动备份时间、格式、内容范围、最近状态与下次条件",
        ) { context ->
            val settings = AutoBackupStore.read(context)
            JSONObject()
                .put("enabled", settings.enabled)
                .put("configured", settings.tree.isNotBlank())
                .put("intervalDays", settings.days)
                .put("hour", settings.hour)
                .put("keepCount", settings.keep)
                .put("chargingOnly", settings.chargingOnly)
                .put("format", settings.options.format.name)
                .put("compression", settings.options.compression.name)
                .put("includeAppFiles", settings.options.includeAppFiles)
                .put("includeNotepad", settings.options.includeNotepad)
                .put("contents", JSONArray(settings.options.contents.map { it.name }))
                .put("lastSuccessAt", AutoBackupStore.lastSuccess(context))
                .put("status", AutoBackupStore.status(context))
        }
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "backup.run_now",
                    title = "立即创建备份",
                    category = "系统",
                    summary = "按已授权的自动备份目录和内容范围排入一次立即备份",
                    danger = EtaControlDanger.WRITE,
                    parameters = JSONObject().put("type", "object").put("properties", JSONObject()),
                    canRead = false,
                ),
                execute = { context, _ ->
                    val settings = AutoBackupStore.read(context)
                    if (settings.tree.isBlank()) {
                        throw EtaControlException("BACKUP_DIRECTORY_REQUIRED", "请先在备份设置中授权保存目录")
                    }
                    io.github.mangi.eta.data.repository.AutoBackupScheduler.enqueue(context)
                    JSONObject().put("queued", true).put("directoryConfigured", true)
                },
            ),
        )
    }

    private fun registerWorkspaceState() {
        registerReadOnly(
            id = "state.shared_folders",
            title = "共享文件夹",
            category = "文件",
            summary = "读取 Android 与 Linux 工作区之间已挂载的文件夹",
        ) { _ ->
            JSONObject().put(
                "folders",
                JSONArray(SharedFolderMounts.current().map { mount ->
                    JSONObject()
                        .put("name", mount.name)
                        .put("sourcePath", mount.sourcePath)
                        .put("linuxPath", "${SharedFolderMounts.LINUX_MOUNTS_ROOT}/${mount.name}")
                }),
            )
        }
    }

    private fun registerRuntimeState() {
        registerReadOnly(
            id = "state.runtime",
            title = "软件运行状态",
            category = "状态",
            summary = "读取进程内存、当前模型、正在运行的任务数和软件管家审计摘要",
        ) { context ->
            val records = AgentRunMetricsStore.records(context).first()
            val process = Runtime.getRuntime()
            val config = RuntimeConfigRepository.currentRuntimeConfig()
            val audit = EtaAppControlAuditStore(context).summary()
            JSONObject()
                .put("processId", android.os.Process.myPid())
                .put("memoryUsedBytes", process.totalMemory() - process.freeMemory())
                .put("memoryMaxBytes", process.maxMemory())
                .put("runningRuns", records.count { it.status == "running" })
                .put("recentRuns", records.size)
                .put("modelConfigured", config != null)
                .put("modelId", config?.model ?: JSONObject.NULL)
                .put("localInference", io.github.mangi.eta.agent.runtime.LocalInferenceScheduling.snapshot())
                .put("activeHosts", kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
                    when (val active = io.github.mangi.eta.agent.runtime.AgentRuntimeClient(context, io.github.mangi.eta.core.AndroidAgentLogger).queryActiveRuns()) {
                        is io.github.mangi.eta.agent.runtime.AgentRuntimeClient.ActiveRunsQuery.Known -> JSONArray(active.runs.map { run ->
                            JSONObject().put("runId", run.runId).put("hosts", JSONArray(run.hostStatesJson)) })
                        else -> JSONObject.NULL
                    }
                })
                .put("audit", audit)
                .put("executionLedger", io.github.mangi.eta.agent.runtime.ExecutionShadowRecorder.inspectionSummary(context, records))
                .put("executionEvidence",io.github.mangi.eta.agent.runtime.NexusExecutionDiagnostics.report(
                    io.github.mangi.eta.data.repository.NexusExecutionStore.get(context),records))
                .put("durableExecutionRuns", JSONArray(
                    io.github.mangi.eta.data.repository.NexusExecutionStore.get(context).recent().map { run ->
                        JSONObject().put("runId",run.request.runId.value)
                            .put("sessionId",run.request.sessionId?.value ?: JSONObject.NULL)
                            .put("source",run.request.source.name).put("mode",run.request.mode.name)
                            .put("state",run.state.name).put("lastSequence",run.lastSequence)
                            .put("checkpointSequence",run.checkpointSequence ?: JSONObject.NULL)
                            .put("recoveryAuthority","legacy-checkpoint")
                    }))
        }
        registerReadOnly(
            id = "state.statistics.today",
            title = "当天运行统计",
            category = "统计",
            summary = "汇总今天的主 Agent、会议室、子 Agent 和任务运行、Token 与工具执行数据",
        ) { context ->
            val records = AgentRunMetricsStore.records(context).first()
            val zone = ZoneId.systemDefault()
            val today = AgentMetricsActivity.date(System.currentTimeMillis(), zone)
            val todayRecords = records.filter { AgentMetricsActivity.date(it.startedAt, zone) == today }
            val usages = records.associate { it.recordId to listOf(it.metrics) }
            val activityDays = AgentMetricsActivity.days(records, usages, today, zone)
            val trend = AgentMetricsTrend.lastWeek(records, zone = zone, usages = usages)
            JSONObject()
                .put("date", today.toString())
                .put("runs", todayRecords.size)
                .put("completedRuns", todayRecords.count { it.status == "succeeded" })
                .put("failedRuns", todayRecords.count { it.status == "failed" })
                .put("interruptedRuns", todayRecords.count { it.status == "interrupted" })
                .put("activeRuns", todayRecords.count { it.status == "running" })
                .put("inputTokens", todayRecords.sumOf { it.metrics.inputTokens ?: 0L })
                .put("outputTokens", todayRecords.sumOf { it.metrics.outputTokens ?: 0L })
                .put("reasoningTokens", todayRecords.sumOf { it.metrics.reasoningTokens ?: 0L })
                .put("toolCalls", todayRecords.sumOf { it.metrics.toolCalls.toLong() })
                .put("failedTools", todayRecords.sumOf { it.metrics.failedTools.toLong() })
                .put("durationMs", todayRecords.sumOf(AgentRunMetricsRecord::durationMs))
                .put("activeStreakDays", AgentMetricsActivity.streak(activityDays, today))
                .put(
                    "sourceBreakdown",
                    JSONObject().also { breakdown ->
                        todayRecords.groupBy(AgentRunMetricsRecord::source).forEach { (source, items) ->
                            breakdown.put(source.ifBlank { "unknown" }, items.size)
                        }
                    },
                )
                .put(
                    "lastSevenDays",
                    JSONArray(trend.map { day ->
                        JSONObject()
                            .put("date", day.date.toString())
                            .put("runs", day.runs)
                            .put("inputTokens", day.inputTokens ?: JSONObject.NULL)
                            .put("outputTokens", day.outputTokens ?: JSONObject.NULL)
                            .put("incompleteUsage", day.incompleteUsage)
                    }),
                )
        }
    }

    private suspend fun currentModelJson(): JSONObject {
        val config = RuntimeConfigRepository.currentRuntimeConfig()
            ?: throw EtaControlException("MODEL_NOT_CONFIGURED", "尚未配置可用模型")
        return JSONObject()
            .put("providerId", config.providerId)
            .put("providerName", config.providerName)
            .put("modelId", config.model)
            .put("modelName", config.modelDisplayName.ifBlank { config.model })
            .put("supportsTools", config.supportsTools)
            .put("contextWindow", config.contextWindow ?: JSONObject.NULL)
    }

    private fun learningSettingsJson(context: Context): JSONObject = JSONObject()
        .put("enabled", LearningSettingsStore.isEnabled(context))
        .put("automaticCaptureEnabled", LearningSettingsStore.automaticCaptureEnabled(context))
        .put("automaticPromotionEnabled", LearningSettingsStore.automaticPromotionEnabled(context))

    private fun registerLearningMutation(
        id: String,
        title: String,
        summary: String,
        danger: EtaControlDanger = EtaControlDanger.CONFIRM,
        execute: suspend (Context, String) -> io.github.mangi.eta.fork.learning.LearningMutationResult,
    ) {
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = id,
                    title = title,
                    category = "智能",
                    summary = summary,
                    danger = danger,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("candidateId", JSONObject().put("type", "string").put("minLength", 1)),
                        )
                        .put("required", JSONArray().put("candidateId"))
                        .put("additionalProperties", false),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    learningMutationResultJson(execute(context, parameters.getString("candidateId")))
                },
            ),
        )
    }

    private fun learningMutationResultJson(
        result: io.github.mangi.eta.fork.learning.LearningMutationResult,
    ): JSONObject = when (result) {
        is io.github.mangi.eta.fork.learning.LearningMutationResult.Success -> JSONObject()
            .put("candidateCount", result.snapshot.candidates.size)
            .put("ruleCount", result.snapshot.rules.size)
        is io.github.mangi.eta.fork.learning.LearningMutationResult.Rejected -> throw EtaControlException(
            "LEARNING_MUTATION_REJECTED",
            result.reason,
        )
    }

    private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) getDouble(key) else null

    private fun AppearanceSettings.toControlJson(): JSONObject = JSONObject()
        .put("skin", skin.persistedValue)
        .put("themeMode", themeMode.persistedValue)
        .put("monetEnabled", monetEnabled)
        .put("paletteStyle", paletteStyle.persistedValue)
        .put("accentColor", accentColor.persistedValue)
        .put("pureBlackEnabled", pureBlackEnabled)
        .put("blurEnabled", blurEnabled)
        .put("topBarBlurStyle", topBarBlurStyle.persistedValue)
        .put("swipeDismissEnabled", swipeDismissEnabled)
        .put("predictiveBackEnabled", predictiveBackEnabled)
        .put("interfaceScale", interfaceScale)
        .put("fontFamily", fontFamily.persistedValue)

    private fun JSONObject.toAppearanceSettings(): AppearanceSettings = AppearanceSettings(
        skin = AppearanceSkin.fromPersistedValue(optString("skin")),
        themeMode = AppearanceThemeMode.fromPersistedValue(optString("themeMode")),
        monetEnabled = optBoolean("monetEnabled", false),
        paletteStyle = AppearancePaletteStyle.fromPersistedValue(optString("paletteStyle")),
        accentColor = AppearanceAccentColor.fromPersistedValue(optString("accentColor")),
        pureBlackEnabled = optBoolean("pureBlackEnabled", false),
        blurEnabled = optBoolean("blurEnabled", true),
        topBarBlurStyle = AppearanceTopBarBlurStyle.fromPersistedValue(optString("topBarBlurStyle")),
        swipeDismissEnabled = optBoolean("swipeDismissEnabled", true),
        predictiveBackEnabled = optBoolean("predictiveBackEnabled", true),
        interfaceScale = optDoubleOrNull("interfaceScale")?.toFloat() ?: 1f,
        fontFamily = AppearanceFontFamily.fromPersistedValue(optString("fontFamily")),
    ).normalized()

    private fun JSONArray?.toStringList(): List<String> {
        val array = this ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }

    private fun ModelFallbackSettings.toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("providerId", providerId ?: JSONObject.NULL)
        .put("modelId", modelId ?: JSONObject.NULL)
        .put("maxAttemptsPerRun", maxAttemptsPerRun)

    private fun ModelCandidateSettings.toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("candidates", JSONArray().also { array ->
            candidates.forEach { candidate ->
                array.put(JSONObject()
                    .put("providerId", candidate.providerId)
                    .put("modelId", candidate.modelId)
                    .put("enabled", candidate.enabled)
                    .put("purpose", candidate.purpose.name)
                    .put("requiredTools", candidate.requiredTools)
                    .put("requiredVision", candidate.requiredVision)
                    .put("minimumContextWindow", candidate.minimumContextWindow ?: JSONObject.NULL)
                    .put("priority", candidate.priority))
            }
        })

    private fun JSONObject.toModelCandidateSettings(): ModelCandidateSettings {
        val candidates = optJSONArray("candidates") ?: JSONArray()
        return ModelCandidateSettings(
            enabled = optBoolean("enabled", false),
            candidates = (0 until candidates.length()).mapNotNull { index ->
                val candidate = candidates.optJSONObject(index) ?: return@mapNotNull null
                ModelCandidateBinding(
                    providerId = candidate.optString("providerId"),
                    modelId = candidate.optString("modelId"),
                    enabled = candidate.optBoolean("enabled", true),
                    purpose = runCatching {
                        ModelCandidatePurpose.valueOf(candidate.optString("purpose").trim().uppercase())
                    }.getOrDefault(ModelCandidatePurpose.CHAT),
                    requiredTools = candidate.optBoolean("requiredTools", false),
                    requiredVision = candidate.optBoolean("requiredVision", false),
                    minimumContextWindow = (candidate.opt("minimumContextWindow") as? Number)?.toInt()
                        ?: candidate.optString("minimumContextWindow").toIntOrNull(),
                    priority = candidate.optInt("priority", 0),
                ).normalized()
            },
        ).normalized()
    }

    private fun ModelRoleSettings.toJson(): JSONObject = JSONObject()
        .put("auxiliaryProviderId", auxiliaryProviderId ?: JSONObject.NULL)
        .put("auxiliaryModelId", auxiliaryModelId ?: JSONObject.NULL)
        .put("compactionProviderId", compactionProviderId ?: JSONObject.NULL)
        .put("compactionModelId", compactionModelId ?: JSONObject.NULL)
        .put("memoryProviderId", memoryProviderId ?: JSONObject.NULL)
        .put("memoryModelId", memoryModelId ?: JSONObject.NULL)
        .put("learningProviderId", learningProviderId ?: JSONObject.NULL)
        .put("learningModelId", learningModelId ?: JSONObject.NULL)
        .put("transcriptProviderId", transcriptProviderId ?: JSONObject.NULL)
        .put("transcriptModelId", transcriptModelId ?: JSONObject.NULL)

    private fun JSONObject.optionalModelRoleId(name: String): String? =
        (opt(name) as? String)?.trim()?.takeIf(String::isNotEmpty)

    private fun JSONObject.toModelRoleSettings(): ModelRoleSettings = ModelRoleSettings(
        auxiliaryProviderId = optionalModelRoleId("auxiliaryProviderId"),
        auxiliaryModelId = optionalModelRoleId("auxiliaryModelId"),
        compactionProviderId = optionalModelRoleId("compactionProviderId"),
        compactionModelId = optionalModelRoleId("compactionModelId"),
        memoryProviderId = optionalModelRoleId("memoryProviderId"),
        memoryModelId = optionalModelRoleId("memoryModelId"),
        learningProviderId = optionalModelRoleId("learningProviderId"),
        learningModelId = optionalModelRoleId("learningModelId"),
        transcriptProviderId = optionalModelRoleId("transcriptProviderId"),
        transcriptModelId = optionalModelRoleId("transcriptModelId"),
    )
}
