package io.github.mangi.eta.agent.control

import android.content.Context
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import io.github.zhh_lgtm.nexus.application.api.SettingsApplyDecision
import io.github.zhh_lgtm.nexus.application.api.SettingsControlPlane
import io.github.zhh_lgtm.nexus.application.api.SettingsMutationPolicy
import io.github.zhh_lgtm.nexus.application.api.SettingsReadResult
import io.github.zhh_lgtm.nexus.application.api.SettingsValue
import io.github.zhh_lgtm.nexus.kernel.api.SettingRisk
import io.github.zhh_lgtm.nexus.kernel.api.SettingsChangeRequest
import io.github.zhh_lgtm.nexus.kernel.api.SettingsChangeResult
import io.github.zhh_lgtm.nexus.kernel.api.SettingsDescriptor
import org.json.JSONArray
import org.json.JSONObject

/** Adapter from the stable Settings control contract to the existing app-control registry. */
internal class NexusSettingsControlPlane(private val context: Context) : SettingsControlPlane {
    private val appContext = context.applicationContext

    override fun list(category: String?): List<SettingsDescriptor> =
        EtaAppControlRegistry.list(category.orEmpty()).map(::descriptor)

    override fun describe(settingId: String): SettingsDescriptor? =
        EtaAppControlRegistry.list().firstOrNull { it.id == settingId }?.let(::descriptor)

    override suspend fun read(settingId: String, actor: SettingsActorKind): SettingsReadResult {
        require(actor != SettingsActorKind.REMOTE_AGENT) { "Remote agents may not read local settings" }
        val descriptor = descriptor(EtaAppControlRegistry.list().firstOrNull { it.id == settingId }
            ?: throw EtaControlException("UNKNOWN_CAPABILITY", "Unknown setting: $settingId"))
        val result = EtaAppControlRegistry.get(appContext, settingId)
        val value = if (descriptor.sensitive) {
            SettingsValue.SecretState(
                configured = result.optBoolean("configured", result.optBoolean("ok", false)),
                fingerprint = result.optString("fingerprint").takeIf(String::isNotBlank),
                updatedAt = result.optLong("updatedAt", 0L).takeIf { it > 0 },
            )
        } else {
            SettingsValue.Text(result.toString())
        }
        return SettingsReadResult(descriptor, value)
    }

    override suspend fun apply(request: SettingsChangeRequest, actor: SettingsActorKind): SettingsChangeResult {
        val info = EtaAppControlRegistry.list().firstOrNull { it.id == request.settingId }
            ?: throw EtaControlException("UNKNOWN_CAPABILITY", "Unknown setting: ${request.settingId}")
        val descriptor = descriptor(info)
        when (SettingsMutationPolicy.decide(descriptor, actor, locallyConfirmed = actor == SettingsActorKind.LOCAL_USER)) {
            SettingsApplyDecision.RemoteRequestOnly -> throw SecurityException("Remote agents may only submit change requests")
            SettingsApplyDecision.RequiresLocalConfirmation,
            SettingsApplyDecision.Apply,
            -> Unit
        }
        val parameters = if (request.clearSecret) {
            JSONObject().put("clear", true)
        } else {
            parseParameters(request.requestedValue)
        }
        val result = EtaAppControlRegistry.execute(appContext, request.settingId, parameters)
        return SettingsChangeResult(
            settingId = request.settingId,
            changed = result.optBoolean("ok", false),
            auditId = result.optString("auditId"),
            undoAvailable = EtaAppControlRegistry.canUndo(request.settingId),
        )
    }

    override suspend fun rollback(auditId: String, actor: SettingsActorKind): SettingsChangeResult {
        require(actor != SettingsActorKind.REMOTE_AGENT) { "Remote agents may not roll back local settings" }
        val result = EtaAppControlRegistry.undo(appContext, auditId)
        return SettingsChangeResult(
            settingId = result.optString("id"),
            changed = result.optBoolean("ok", false),
            auditId = result.optString("auditId"),
            undoAvailable = false,
        )
    }

    private fun descriptor(info: EtaControlCapabilityInfo): SettingsDescriptor = SettingsDescriptor(
        id = info.id,
        title = info.title,
        valueType = info.parameters.optString("type", "object"),
        scope = info.category,
        risk = when (info.danger) {
            EtaControlDanger.SAFE -> SettingRisk.SAFE
            EtaControlDanger.WRITE -> SettingRisk.WRITE
            EtaControlDanger.CONFIRM -> SettingRisk.SECURITY
        },
        sensitive = isSensitiveId(info.id),
        requiresRemoteApproval = true,
        undoable = EtaAppControlRegistry.canUndo(info.id),
    )

    private fun parseParameters(raw: String?): JSONObject {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return JSONObject()
        return runCatching { JSONObject(value) }.getOrElse {
            JSONObject().put(
                "value",
                when {
                    value == "true" || value == "false" -> value.toBooleanStrict()
                    value.toDoubleOrNull() != null -> value.toDouble()
                    value.startsWith("[") -> JSONArray(value)
                    else -> value
                },
            )
        }
    }

    private fun isSensitiveId(id: String): Boolean {
        val normalized = id.lowercase()
        return listOf("token", "secret", "credential", "password", "api_key", "private_key")
            .any(normalized::contains)
    }
}
