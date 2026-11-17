package io.github.mangi.eta.agent.control

import android.app.Application
import android.content.Intent
import android.provider.DocumentsContract
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.mangi.eta.data.repository.*
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackupSettingsControlTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val plane get() = NexusSettingsControlPlane.get(context)
    private lateinit var manager: WorkManager
    private val tree = DocumentsContract.buildTreeDocumentUri("backup.test", "root")
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private val options = EtaBackupOptions(EtaBackupFormat.JSON, setOf(EtaBackupContent.MEMORY))

    @Before fun setup() {
        manager = runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(context, Configuration.Builder().build())
            WorkManager.getInstance(context)
        }
    }

    @After fun close() { manager.cancelAllWork().result.get(10, TimeUnit.SECONDS) }

    @Test fun actualUiSaveReadAndUndoUseOneAuditAndPreserveAllOptions() = runBlocking {
        val before = AutoBackupStore.read(context)
        val value = before.copy(days = 5, hour = 22, keep = 3, chargingOnly = false, options = options)
        assertEquals(value, AutoBackupSettingsRepository.saveFromUi(context, before, value))
        val entry = plane.audit().single { it.optString("operation") == "execute" }
        assertEquals("settings.backup.automatic", entry.getString("capabilityId"))
        assertEquals(SettingsActorKind.LOCAL_USER.name, entry.getString("actor"))
        assertEquals("backup_settings", entry.getString("sourceAgent"))
        assertTrue(entry.getBoolean("rollbackSafe"))
        val current = plane.readJson("settings.backup.automatic", SettingsActorKind.LOCAL_AGENT)
        assertEquals(22, current.getInt("hour"))
        assertEquals("MEMORY", current.getJSONArray("contents").getString(0))
        plane.rollbackJson(entry.getString("id"), SettingsActorKind.LOCAL_USER)
        assertEquals(before, AutoBackupStore.read(context))
    }

    @Test fun enabledConfigurationReachesOriginalSchedulerAndRevokedDirectoryCanBeDisabled() = runBlocking {
        context.contentResolver.takePersistableUriPermission(tree, flags)
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree })
        val enabled = AutoBackupSettings(enabled = true, tree = tree.toString(), options = options)
        AutoBackupSettingsRepository.save(context, enabled)
        val scheduled = manager.getWorkInfosForUniqueWork("eta-auto-backup").get(10, TimeUnit.SECONDS)
        assertEquals(1, scheduled.count { !it.state.isFinished })
        assertEquals(WorkInfo.State.ENQUEUED, scheduled.single().state)
        context.contentResolver.releasePersistableUriPermission(tree, flags)
        AutoBackupSettingsRepository.save(context, enabled.copy(enabled = false))
        assertTrue(manager.getWorkInfosForUniqueWork("eta-auto-backup").get(10, TimeUnit.SECONDS).all { it.state.isFinished })
        assertTrue(runCatching { AutoBackupSettingsRepository.save(context, enabled) }.isFailure)
        assertFalse(AutoBackupStore.read(context).enabled)
    }

    @Test fun unknownDirectoryInvalidContentAndRemoteMutationDoNotChangeStoredConfiguration() = runBlocking {
        val original = AutoBackupStore.read(context)
        val value = original.copy(enabled = true, tree = tree.toString(), options = options)
        assertTrue(runCatching { AutoBackupSettingsRepository.save(context, value) }.isFailure)
        assertEquals(original, AutoBackupStore.read(context))
        val bad = AutoBackupSettingsRepository.encode(original).put("intervalDays", 0)
        assertTrue(runCatching { plane.applyJson("settings.backup.automatic", JSONObject().put("value", bad), SettingsActorKind.LOCAL_AGENT) }.isFailure)
        assertTrue(runCatching { plane.applyJson("settings.backup.automatic", JSONObject().put("value", AutoBackupSettingsRepository.encode(original)), SettingsActorKind.REMOTE_AGENT) }.isFailure)
        assertEquals(original, AutoBackupStore.read(context))
        Unit
    }

    @Test fun undoRefusesToOverwriteALaterSave() = runBlocking {
        val original = AutoBackupStore.read(context)
        AutoBackupSettingsRepository.save(context, original.copy(hour = 9))
        val first = plane.audit().single { it.optString("operation") == "execute" }.getString("id")
        AutoBackupSettingsRepository.save(context, original.copy(hour = 10))
        assertEquals("SETTINGS_CHANGED_SINCE_AUDIT", runCatching { plane.rollbackJson(first, SettingsActorKind.LOCAL_USER) }.exceptionOrNull()?.message)
        assertEquals(10, AutoBackupStore.read(context).hour)
    }

    @Test fun staleUiSaveDoesNotReplaceNewerSettingsOrPassword() = runBlocking {
        val original = AutoBackupStore.read(context)
        val newer = original.copy(hour = (original.hour + 1) % 24)
        AutoBackupSettingsRepository.save(context, newer)
        val failure = runCatching {
            AutoBackupSettingsRepository.saveFromUi(context, original, original.copy(days = 9), "new-password")
        }.exceptionOrNull()
        assertEquals("自动备份设置已更改，请核对后重试", failure?.message)
        assertEquals(newer, AutoBackupStore.read(context))
        assertFalse(AutoBackupPassword.configured(context))
    }

    @Test fun disabledBackupDoesNotReadUnavailablePassword() = runBlocking {
        val disabled = AutoBackupStore.read(context).copy(enabled = false)
        AutoBackupStore.save(context, disabled)
        context.getSharedPreferences("eta_auto_backup_secret", 0).edit().putString("ciphertext", "unavailable").commit()
        try {
            assertNull(AutoBackupSettingsRepository.snapshotForBackup(context, manual = false))
        } finally {
            context.getSharedPreferences("eta_auto_backup_secret", 0).edit().clear().commit()
        }
    }
}
