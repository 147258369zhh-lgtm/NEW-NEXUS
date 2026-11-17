package io.github.mangi.eta.agent.control

import io.github.mangi.eta.data.repository.AutoBackupSettingsRepository
import io.github.mangi.eta.data.repository.AutoBackupStore
import io.github.mangi.eta.data.repository.AutoBackupPassword
import io.github.mangi.eta.data.repository.EtaBackupCompression
import io.github.mangi.eta.data.repository.EtaBackupContent
import io.github.mangi.eta.data.repository.EtaBackupFormat
import org.json.JSONArray
import org.json.JSONObject

internal object BackupSettingsControlProvider : EtaControlProvider {
    override val providerId = "backup_settings"

    override fun capabilities(): List<EtaControlRegistration> {
        fun field(type: String) = JSONObject().put("type", type)
        fun number(min: Int, max: Int) = field("integer").put("minimum", min).put("maximum", max)
        fun choice(values: List<String>) = field("string").put("enum", JSONArray(values))
        val fields = JSONObject().put("enabled", field("boolean")).put("directoryTree", field("string").put("maxLength", 2048))
            .put("intervalDays", number(1, 30)).put("hour", number(0, 23)).put("keepCount", number(1, 30))
            .put("chargingOnly", field("boolean")).put("unmeteredOnly", field("boolean"))
            .put("format", choice(EtaBackupFormat.entries.map { it.name }))
            .put("contents", field("array").put("items", choice(EtaBackupContent.entries.map { it.name }))
                .put("minItems", 1).put("maxItems", EtaBackupContent.entries.size).put("uniqueItems", true))
            .put("includeAppFiles", field("boolean")).put("includeNotepad", field("boolean")).put("compression", choice(EtaBackupCompression.entries.map { it.name }))
        val value = field("object").put("properties", fields).put("required", JSONArray(fields.keys().asSequence().filter { it != "includeNotepad" }.toList()))
            .put("additionalProperties", false)
        return listOf(EtaControlRegistration(
            info = EtaControlCapabilityInfo("settings.backup.automatic", "自动备份设置", "备份", "修改周期、内容和已在本机授权的目录；加密密码只能在备份设置中填写",
                EtaControlDanger.WRITE, field("object").put("properties", JSONObject().put("value", value))
                    .put("required", JSONArray().put("value")).put("additionalProperties", false), true),
            read = { AutoBackupSettingsRepository.encode(AutoBackupStore.read(it)) },
            snapshot = { context, args -> AutoBackupSettingsRepository.encode(AutoBackupStore.read(context)).apply {
                if (args.optBoolean("privateChange")) {
                    put("authenticationEpoch", AutoBackupPassword.auditRevision(context))
                    put("credentials", "private change; re-entry required")
                }
            } },
            rollback = { context, before ->
                AutoBackupSettingsRepository.apply(context, AutoBackupSettingsRepository.decode(before))
                AutoBackupSettingsRepository.encode(AutoBackupStore.read(context))
            },
            execute = { context, parameters ->
                AutoBackupSettingsRepository.apply(context, AutoBackupSettingsRepository.decode(parameters.getJSONObject("value")))
                AutoBackupSettingsRepository.encode(AutoBackupStore.read(context))
            },
        ))
    }
}
