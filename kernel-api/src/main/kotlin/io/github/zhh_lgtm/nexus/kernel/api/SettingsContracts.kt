package io.github.zhh_lgtm.nexus.kernel.api

enum class SettingRisk {
    SAFE,
    WRITE,
    SECURITY,
    DESTRUCTIVE,
}

data class SettingsDescriptor(
    val id: String,
    val title: String,
    val valueType: String,
    val scope: String,
    val risk: SettingRisk,
    val sensitive: Boolean,
    val requiresRemoteApproval: Boolean,
    val undoable: Boolean,
)

data class SettingsChangeRequest(
    val settingId: String,
    val sourceAgent: String,
    val sourceRunId: RunId?,
    val reason: String,
    val requestedValue: String?,
    val clearSecret: Boolean = false,
)

data class SettingsChangeResult(
    val settingId: String,
    val changed: Boolean,
    val auditId: String,
    val undoAvailable: Boolean,
)
