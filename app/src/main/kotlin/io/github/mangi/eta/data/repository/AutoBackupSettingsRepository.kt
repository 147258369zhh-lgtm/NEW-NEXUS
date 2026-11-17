package io.github.mangi.eta.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mangi.eta.agent.control.NexusSettingsControlPlane
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object AutoBackupSettingsRepository {
    private val snapshotLock = Mutex()

    internal suspend fun snapshotForBackup(context: Context, manual: Boolean): Pair<AutoBackupSettings, String?>? = snapshotLock.withLock {
        val settings = AutoBackupStore.read(context)
        if (!manual && (!settings.enabled || !AutoBackupSchedule.isDue(settings, AutoBackupStore.lastSuccess(context)))) {
            null
        } else settings to AutoBackupPassword.read(context)
    }

    suspend fun save(context: Context, value: AutoBackupSettings): AutoBackupSettings {
        NexusSettingsControlPlane.get(context).applyJson("settings.backup.automatic",
            JSONObject().put("value", encode(value)), SettingsActorKind.LOCAL_USER, sourceAgent = "backup_settings")
        return AutoBackupStore.read(context)
    }

    suspend fun saveFromUi(
        context: Context,
        expected: AutoBackupSettings,
        value: AutoBackupSettings,
        password: String? = null,
    ): AutoBackupSettings {
        NexusSettingsControlPlane.get(context).mutateOwnedSettingsFromUi(
            "settings.backup.automatic", JSONObject().put("value", encode(value)).put("privateChange", password != null), "backup_settings",
        ) {
            snapshotLock.withLock {
                check(AutoBackupStore.read(context) == expected) { "自动备份设置已更改，请核对后重试" }
                val previousPassword = if (password != null) AutoBackupPassword.read(context).orEmpty() else null
                try {
                    if (password != null) AutoBackupPassword.save(context, password)
                    applyUnlocked(context, value)
                } catch (failure: Throwable) {
                    if (previousPassword != null && AutoBackupStore.read(context) == expected) {
                        withContext(NonCancellable) { runCatching { AutoBackupPassword.save(context, previousPassword) } }
                    }
                    throw failure
                }
                encode(AutoBackupStore.read(context))
            }
        }
        return AutoBackupStore.read(context)
    }

    suspend fun savePasswordFromUi(context: Context, expected: AutoBackupSettings, password: String) {
        NexusSettingsControlPlane.get(context).mutateOwnedSettingsFromUi(
            "settings.backup.automatic", JSONObject().put("value", encode(expected)).put("privateChange", true), "backup_settings",
        ) {
            snapshotLock.withLock {
                check(AutoBackupStore.read(context) == expected) { "自动备份设置已更改，请核对后重试" }
                if (password.isEmpty() && expected.enabled && EtaBackupOperations.requiresPassword(context, expected.options)) {
                    error("当前自动备份需要加密密码")
                }
                AutoBackupPassword.save(context, password)
                encode(AutoBackupStore.read(context))
            }
        }
    }

    internal suspend fun apply(context: Context, value: AutoBackupSettings) = snapshotLock.withLock {
        applyUnlocked(context, value)
    }

    private suspend fun applyUnlocked(context: Context, value: AutoBackupSettings) {
        require(value.days in 1..30 && value.hour in 0..23 && value.keep in 1..30) { "自动备份时间或保留份数无效" }
        val normalized = value.copy(options = value.options.validated())
        val previous = AutoBackupStore.read(context)
        if (normalized.enabled || normalized.tree.isNotBlank() && normalized.tree != previous.tree) {
            val uri = Uri.parse(normalized.tree)
            require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri) &&
                context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }) {
                "请在备份设置中重新授权可读写目录"
            }
        }
        if (normalized.enabled && EtaBackupOperations.requiresPassword(context, normalized.options)) {
            require(!AutoBackupPassword.read(context).isNullOrEmpty()) { "请先在备份设置中保存加密密码" }
        }
        AutoBackupStore.save(context, normalized)
        AutoBackupScheduler.sync(context)
    }

    internal fun encode(value: AutoBackupSettings): JSONObject = JSONObject()
        .put("enabled", value.enabled).put("directoryTree", value.tree).put("intervalDays", value.days)
        .put("hour", value.hour).put("keepCount", value.keep).put("chargingOnly", value.chargingOnly)
        .put("unmeteredOnly", value.unmeteredOnly)
        .put("format", value.options.format.name).put("contents", JSONArray(value.options.contents.map { it.name }.sorted()))
        .put("includeAppFiles", value.options.includeAppFiles).put("includeNotepad", value.options.includeNotepad).put("compression", value.options.compression.name)

    internal fun decode(value: JSONObject): AutoBackupSettings {
        val contents = value.getJSONArray("contents")
        return AutoBackupSettings(value.getBoolean("enabled"), value.getString("directoryTree"),
            value.getInt("intervalDays"), value.getInt("hour"), value.getInt("keepCount"), value.getBoolean("chargingOnly"),
            EtaBackupOptions(format = EtaBackupFormat.valueOf(value.getString("format")),
                contents = (0 until contents.length()).map { EtaBackupContent.valueOf(contents.getString(it)) }.toSet(),
                includeAppFiles = value.getBoolean("includeAppFiles"), includeNotepad = value.optBoolean("includeNotepad", true),
                compression = EtaBackupCompression.valueOf(value.getString("compression"))),
            value.optBoolean("unmeteredOnly", true))
    }
}
