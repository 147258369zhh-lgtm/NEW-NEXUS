package io.github.mangi.eta.agent.control

import android.content.Context
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.zhh_lgtm.nexus.application.api.*
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One application facade over ETA's existing settings registry, snapshots and audit. */
internal class NexusSettingsControlPlane(private val context: Context) : SettingsControlPlane {
    private val appContext = context.applicationContext
    private val grantStore = SettingsGrantStore(appContext)

    override fun list(category: String?): List<SettingsDescriptor> = EtaAppControlRegistry.list(category.orEmpty())
        .filter { it.id.startsWith("settings.") }.map(::descriptor)

    override fun describe(settingId: String): SettingsDescriptor? = infoOrNull(settingId)?.let(::descriptor)

    override suspend fun read(settingId: String, actor: SettingsActorKind): SettingsReadResult {
        val descriptor = describe(settingId) ?: throw EtaControlException("UNKNOWN_SETTING", "设置不存在")
        val result = readJson(settingId, actor)
        val value = if (descriptor.sensitive) SettingsValue.SecretState(result.optBoolean("configured", false),
            result.optString("fingerprint").takeIf(String::isNotBlank), result.optLong("updatedAt").takeIf { it > 0 })
        else SettingsValue.Text(result.toString())
        return SettingsReadResult(descriptor, value)
    }

    suspend fun readJson(settingId: String, actor: SettingsActorKind): JSONObject {
        requireLocal(actor)
        checkNotNull(infoOrNull(settingId)) { "UNKNOWN_SETTING" }
        return EtaAppControlRegistry.get(appContext, settingId)
    }

    override suspend fun apply(request: SettingsChangeRequest, actor: SettingsActorKind): SettingsChangeResult {
        if (request.clearSecret) throw EtaControlException("SECRET_CHANGE_UNSUPPORTED", "该设置没有声明清除凭据接口")
        val result = applyJson(request.settingId, parseParameters(request.requestedValue), actor, request.sourceAgent,
            request.sourceRunId, request.reason)
        return SettingsChangeResult(request.settingId, result.optBoolean("changed", true), result.getString("auditId"),
            EtaAppControlRegistry.canUndo(request.settingId))
    }

    suspend fun applyJson(settingId: String, parameters: JSONObject, actor: SettingsActorKind,
        sourceAgent: String = "local_user", sourceRunId: RunId? = null, reason: String = "settings_change"): JSONObject {
        checkNotNull(infoOrNull(settingId)) { "UNKNOWN_SETTING" }
        if (settingId == "settings.agent_permission" && actor != SettingsActorKind.LOCAL_USER) {
            throw EtaControlException("PERMISSION_MODE_LOCAL_USER_ONLY", "审批模式只能由用户在本机设置中更改，Agent 不能自行提权")
        }
        val grant = authorizeChange(settingId, actor, sourceAgent, sourceRunId?.value)
        val claimed = grant?.singleUse == true
        if (claimed && !grantStore.consume(checkNotNull(grant).id)) {
            throw EtaControlException("SETTINGS_GRANT_ALREADY_USED", "这次单次设置授权已失效或被其他操作领取")
        }
        return withContext(SettingsControlOrigin(actor, sourceAgent, sourceRunId?.value, reason, grant?.id, claimed)) {
            EtaAppControlRegistry.execute(appContext, settingId, parameters)
        }
    }

    fun listGrants(): List<SettingsGrantSnapshot> = grantStore.list()

    fun grantsFor(subject: SettingsGrantSubject): List<SettingsGrantSnapshot> = grantStore.listFor(subject)

    fun grantVersion(subject: SettingsGrantSubject): Long = grantStore.currentVersionFor(subject)

    /** Only the local user may widen authority; agents must ask through the normal request path. */
    fun issueGrant(
        subject: SettingsGrantSubject,
        scope: List<String>,
        validUntil: Long,
        singleUse: Boolean = false,
        actor: SettingsActorKind = SettingsActorKind.LOCAL_USER,
    ): SettingsGrantSnapshot {
        requireLocalOwner(actor)
        return grantStore.issue(subject, scope, validUntil, singleUse)
    }

    fun revokeGrant(grantId: String, actor: SettingsActorKind = SettingsActorKind.LOCAL_USER): Boolean {
        requireLocalOwner(actor)
        return grantStore.revoke(grantId)
    }

    /** Revokes every live grant for the subject and advances its version. */
    fun revokeGrantsFor(
        subject: SettingsGrantSubject,
        actor: SettingsActorKind = SettingsActorKind.LOCAL_USER,
    ): Int {
        requireLocalOwner(actor)
        return grantStore.revokeSubject(subject)
    }

    fun grantDecision(
        settingId: String,
        actor: SettingsActorKind,
        sourceAgent: String,
        sourceRunId: String? = null,
    ): SettingsGrantEvaluation = grantStore.authorize(SettingsGrantSubject(actor, sourceAgent, sourceRunId), settingId)

    suspend fun updateAppearance(transform: (AppearanceSettings) -> AppearanceSettings): JSONObject =
        withContext(SettingsControlOrigin(SettingsActorKind.LOCAL_USER, "appearance_settings")) {
            EtaAppControlRegistry.updateAppearance(appContext, transform)
        }

    suspend fun saveModel(providerId: String, draft: io.github.mangi.eta.data.model.Model): io.github.mangi.eta.data.model.Model =
        withContext(SettingsControlOrigin(SettingsActorKind.LOCAL_USER, "model_settings")) {
            EtaAppControlRegistry.saveModel(appContext, providerId, draft)
        }

    suspend fun setRuntimePreferenceFromUi(key: String, enabled: Boolean): JSONObject =
        withContext(SettingsControlOrigin(SettingsActorKind.LOCAL_USER, "runtime_settings")) {
            EtaAppControlRegistry.setRuntimePreferenceFromUi(appContext, key, enabled)
        }

    internal suspend fun mutateOwnedSettingsFromUi(id: String, parameters: JSONObject, source: String,
        execute: suspend () -> JSONObject): JSONObject = withContext(SettingsControlOrigin(SettingsActorKind.LOCAL_USER, source)) {
        EtaAppControlRegistry.mutateOwnedSettingsFromUi(appContext, id, parameters, execute)
    }

    suspend fun rollbackJson(auditId: String, actor: SettingsActorKind, sourceAgent: String = "local_user",
        sourceRunId: RunId? = null): JSONObject {
        requireLocal(actor)
        val entry = EtaAppControlAuditStore(appContext).find(auditId) ?: error("AUDIT_NOT_FOUND")
        checkNotNull(infoOrNull(entry.optString("capabilityId"))) { "NOT_A_SETTINGS_CHANGE" }
        return withContext(SettingsControlOrigin(actor, sourceAgent, sourceRunId?.value, "rollback:$auditId")) {
            EtaAppControlRegistry.undo(appContext, auditId)
        }
    }

    override suspend fun rollback(auditId: String, actor: SettingsActorKind): SettingsChangeResult {
        val result = rollbackJson(auditId, actor)
        return SettingsChangeResult(result.getString("id"), true, result.getString("auditId"), false)
    }

    fun audit(limit: Int = 100): List<JSONObject> = EtaAppControlAuditStore(appContext).list(limit)
        .filter { it.optString("capabilityId").startsWith("settings.") }

    private fun infoOrNull(id: String) = EtaAppControlRegistry.list().firstOrNull { it.id == id && id.startsWith("settings.") }

    private fun authorizeChange(
        settingId: String,
        actor: SettingsActorKind,
        sourceAgent: String,
        sourceRunId: String?,
    ): SettingsGrantSnapshot? {
        if (actor == SettingsActorKind.LOCAL_USER) return null
        val evaluation = grantStore.authorize(SettingsGrantSubject(actor, sourceAgent, sourceRunId), settingId)
        val decision = evaluation.decision
        if (decision is SettingsGrantDecision.Denied) throw EtaControlException(decision.code, decision.reason)
        return evaluation.grant
    }
    private fun requireLocal(actor: SettingsActorKind) {
        if (actor == SettingsActorKind.REMOTE_AGENT) throw EtaControlException("REMOTE_SETTINGS_REQUEST_ONLY", "远端 Agent 只能提出设置修改请求")
    }
    private fun requireLocalOwner(actor: SettingsActorKind) {
        if (actor != SettingsActorKind.LOCAL_USER || io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller != null) {
            throw EtaControlException("LOCAL_USER_AUTHORITY_REQUIRED", "只有本机用户可以签发或撤销授权")
        }
    }

    private fun descriptor(info: EtaControlCapabilityInfo) = SettingsDescriptor(info.id, info.title,
        info.parameters.optString("type", "object"), info.category, when {
            info.id.endsWith(".clear") -> SettingRisk.DESTRUCTIVE
            info.danger == EtaControlDanger.CONFIRM -> SettingRisk.SECURITY
            info.danger == EtaControlDanger.WRITE -> SettingRisk.WRITE
            else -> SettingRisk.SAFE
        }, sensitive = Regex("token|secret|credential|password|api.?key", RegexOption.IGNORE_CASE).containsMatchIn(info.id),
        requiresRemoteApproval = true, undoable = EtaAppControlRegistry.canUndo(info.id))

    private fun parseParameters(raw: String?): JSONObject {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return JSONObject()
        return if (value.startsWith("{")) JSONObject(value) else JSONObject().put("value", when {
            value == "true" || value == "false" -> value.toBooleanStrict()
            value.toDoubleOrNull() != null -> value.toDouble()
            value.startsWith("[") -> JSONArray(value)
            else -> value
        })
    }

    companion object {
        @Volatile private var instance: NexusSettingsControlPlane? = null
        fun get(context: Context): NexusSettingsControlPlane = synchronized(this) {
            instance?.takeIf { it.appContext === context.applicationContext }
                ?: NexusSettingsControlPlane(context).also { instance = it }
        }
    }
}
