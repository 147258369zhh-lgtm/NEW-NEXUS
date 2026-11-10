package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.SettingRisk
import io.github.zhh_lgtm.nexus.kernel.api.SettingsChangeRequest
import io.github.zhh_lgtm.nexus.kernel.api.SettingsChangeResult
import io.github.zhh_lgtm.nexus.kernel.api.SettingsDescriptor

enum class SettingsActorKind {
    LOCAL_AGENT,
    SOFTWARE_BUTLER,
    LOCAL_USER,
    REMOTE_AGENT,
}

sealed interface SettingsValue {
    data class Text(val value: String) : SettingsValue
    data class BooleanValue(val value: Boolean) : SettingsValue
    data class NumberValue(val value: Double) : SettingsValue
    data class EnumValue(val value: String) : SettingsValue
    data class SecretState(
        val configured: Boolean,
        val fingerprint: String?,
        val updatedAt: Long?,
    ) : SettingsValue
}

data class SettingsReadResult(
    val descriptor: SettingsDescriptor,
    val value: SettingsValue,
)

sealed interface SettingsApplyDecision {
    data object Apply : SettingsApplyDecision
    data object RequiresLocalConfirmation : SettingsApplyDecision
    data object RemoteRequestOnly : SettingsApplyDecision
}

object SettingsMutationPolicy {
    fun decide(
        descriptor: SettingsDescriptor,
        actor: SettingsActorKind,
        locallyConfirmed: Boolean,
    ): SettingsApplyDecision {
        if (actor == SettingsActorKind.REMOTE_AGENT) return SettingsApplyDecision.RemoteRequestOnly
        if (actor == SettingsActorKind.LOCAL_USER) return SettingsApplyDecision.Apply
        val needsConfirmation = descriptor.risk == SettingRisk.SECURITY || descriptor.risk == SettingRisk.DESTRUCTIVE
        return if (needsConfirmation && !locallyConfirmed) {
            SettingsApplyDecision.RequiresLocalConfirmation
        } else {
            SettingsApplyDecision.Apply
        }
    }
}

interface SettingsControlPlane {
    fun list(category: String? = null): List<SettingsDescriptor>
    fun describe(settingId: String): SettingsDescriptor?
    suspend fun read(settingId: String, actor: SettingsActorKind): SettingsReadResult
    suspend fun apply(request: SettingsChangeRequest, actor: SettingsActorKind): SettingsChangeResult
    suspend fun rollback(auditId: String, actor: SettingsActorKind): SettingsChangeResult
}
