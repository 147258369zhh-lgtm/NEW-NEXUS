package io.github.mangi.eta.data.repository

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import androidx.work.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import androidx.core.app.NotificationCompat
import io.github.mangi.eta.agent.memory.MemoryAutomationCapture
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

internal object EtaBackupOperations {
    private val mutex = Mutex()
    @Volatile var dataOperationActive: Boolean = false
        private set
    suspend fun <T> exclusive(backup: Boolean = false, block: suspend () -> T): T {
        if (!mutex.tryLock()) throw EtaBackupBusyException()
        if (backup) dataOperationActive = true
        try { return block() } finally { if (backup) dataOperationActive = false; mutex.unlock() }
    }

    suspend fun requireIdle(context: Context) {
        check(!EtaFullBackupArchive.hasPendingRestore(context)) { "有待应用的恢复，请先重启完成恢复" }
        check(MemoryAutomationCapture.activeRuns.get() == 0) { "请等待 Agent 任务结束后再备份" }
        val active = runInterruptible(Dispatchers.IO) { AgentRuntimeClient(context, AndroidAgentLogger).queryActiveRuns() }
        check(active is AgentRuntimeClient.ActiveRunsQuery.Known && active.runIds.isEmpty()) { "Agent 正在运行或状态不可用，稍后重试" }
    }

    suspend fun export(context: Context, output: java.io.OutputStream, password: String?, options: EtaBackupOptions,
        progress: (EtaBackupProgress) -> Unit): EtaBackupSummary {
        val validated = options.validated()
        if (password.isNullOrEmpty() && EtaBackupContent.MODELS in validated.contents &&
            io.github.mangi.eta.data.db.EtaDatabase.get(context).providerDao().providers().any { it.provider.apiKey.isNotBlank() }) {
            throw EtaBackupException("模型配置包含 API 密钥，请设置备份密码")
        }
        if (validated.format == EtaBackupFormat.FULL) return EtaFullBackupArchive.export(
            context = context,
            output = output,
            password = password,
            onProgress = progress,
            includeAppFiles = validated.includeAppFiles,
            includeNotepad = validated.includeNotepad,
            compression = validated.compression,
        )
        progress(EtaBackupProgress("logical_snapshot"))
        val encrypted = password?.takeIf { it.isNotEmpty() }?.let { EtaBackupEncryptedOutputStream(output, it) }
        val result = EtaBackupRepository.export(
            context = context,
            output = encrypted ?: output,
            contents = validated.contents,
            onProgress = progress,
        )
        encrypted?.finish()
        progress(EtaBackupProgress("export_complete", records = result.messageCount.toLong()))
        return result.copy(encrypted = encrypted != null)
    }

    suspend fun requiresPassword(context: Context, options: EtaBackupOptions): Boolean = withContext(Dispatchers.IO) {
        if (options.format == EtaBackupFormat.FULL) EtaFullBackupArchive.requiresEncryption(context)
        else EtaBackupContent.MODELS in options.contents && io.github.mangi.eta.data.db.EtaDatabase.get(context)
            .providerDao().providers().any { it.provider.apiKey.isNotBlank() }
    }
}

internal class EtaBackupBusyException : IllegalStateException("备份、恢复或记忆整理正在进行，请稍后再试")

internal data class AutoBackupSettings(
    val enabled: Boolean = false,
    val tree: String = "",
    val days: Int = 3,
    val hour: Int = 3,
    val keep: Int = 7,
    val chargingOnly: Boolean = true,
    val options: EtaBackupOptions = EtaBackupOptions(),
    /** Avoid metered networks when the selected document provider uploads the archive. */
    val unmeteredOnly: Boolean = true,
)

internal object AutoBackupStore {
    private fun prefs(context: Context) = context.getSharedPreferences("eta_auto_backup", Context.MODE_PRIVATE)
    fun observeSettings(context: Context): kotlinx.coroutines.flow.Flow<AutoBackupSettings> = kotlinx.coroutines.flow.callbackFlow {
        val preferences = prefs(context.applicationContext)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != "status" && key != "last_success" && key != "schedule_key") trySend(read(context))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(read(context))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun observeStatus(context: Context): kotlinx.coroutines.flow.Flow<Pair<String, Long>> = kotlinx.coroutines.flow.callbackFlow {
        val preferences = prefs(context.applicationContext)
        fun publish() { trySend(status(context) to lastSuccess(context)) }
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "status" || key == "last_success") publish()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        publish()
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun read(context: Context): AutoBackupSettings {
        val p = prefs(context)
        val format = EtaBackupFormat.entries.firstOrNull { it.name == p.getString("format", "FULL") } ?: EtaBackupFormat.FULL
        val contents = p.getStringSet("contents", EtaBackupContent.entries.map { it.name }.toSet()).orEmpty()
        val compression = EtaBackupCompression.entries.firstOrNull { it.name == p.getString("compression", "FAST") }
            ?: EtaBackupCompression.FAST
        return AutoBackupSettings(p.getBoolean("enabled", false), p.getString("tree", "").orEmpty(),
            p.getInt("days", 3).coerceIn(1, 30), p.getInt("hour", 3).coerceIn(0, 23), p.getInt("keep", 7).coerceIn(1, 30),
            p.getBoolean("charging", true), EtaBackupOptions(
                format = format,
                contents = EtaBackupContent.entries.filter { it.name in contents }.toSet().ifEmpty { EtaBackupContent.entries.toSet() },
                includeAppFiles = p.getBoolean("include_files", false),
                includeNotepad = p.getBoolean("include_notepad", true),
                compression = compression,
            ), p.getBoolean("unmetered", true))
    }
    fun save(context: Context, value: AutoBackupSettings) {
        value.options.validated()
        check(prefs(context).edit().putBoolean("enabled", value.enabled).putString("tree", value.tree)
            .putInt("days", value.days.coerceIn(1, 30)).putInt("hour", value.hour.coerceIn(0, 23)).putInt("keep", value.keep.coerceIn(1, 30))
            .putBoolean("charging", value.chargingOnly).putString("format", value.options.format.name)
            .putStringSet("contents", value.options.contents.map { it.name }.toSet())
            .putBoolean("include_files", value.options.includeAppFiles)
            .putBoolean("include_notepad", value.options.includeNotepad)
            .putString("compression", value.options.compression.name)
            .putBoolean("unmetered", value.unmeteredOnly).commit())
    }
    fun lastSuccess(context: Context) = prefs(context).getLong("last_success", 0)
    fun scheduleKey(context: Context) = prefs(context).getString("schedule_key", "")
    fun scheduleKey(context: Context, value: String) { prefs(context).edit().putString("schedule_key", value).commit() }
    fun status(context: Context): String = prefs(context).getString("status", "尚未自动备份").orEmpty()
    fun status(context: Context, value: String, success: Boolean = false) {
        prefs(context).edit().putString("status", value).apply {
            if (success) putLong("last_success", System.currentTimeMillis())
        }.commit()
    }
}

internal object AutoBackupScheduler {
    private const val PERIODIC = "eta-auto-backup"
    private const val MANUAL = "eta-auto-backup-now"
    fun observeActivity(context: Context): Flow<AutoBackupActivity> {
        val manager = WorkManager.getInstance(context.applicationContext)
        return combine(manager.getWorkInfosForUniqueWorkFlow(PERIODIC), manager.getWorkInfosForUniqueWorkFlow(MANUAL)) { periodic, immediate ->
            AutoBackupActivity(
                running = (periodic + immediate).any { it.state == WorkInfo.State.RUNNING },
                immediatePending = immediate.any { !it.state.isFinished },
            )
        }
    }
    fun sync(context: Context) {
        val settings = AutoBackupStore.read(context)
        val manager = WorkManager.getInstance(context)
        if (!settings.enabled || settings.tree.isBlank()) { manager.cancelUniqueWork(PERIODIC); return }
        val now = ZonedDateTime.now()
        val lastSuccess = AutoBackupStore.lastSuccess(context)
        val next = AutoBackupSchedule.nextAttempt(settings, lastSuccess, now)
        // Catch up after the device was offline instead of waiting for the next calendar slot.
        val delay = if (AutoBackupSchedule.isOverdue(settings, lastSuccess, now)) 0L
        else java.time.Duration.between(now, next).toMillis()
        // Check daily so a manual backup cannot make the next due backup skip an entire interval.
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(settings.chargingOnly)
            .apply { if (settings.unmeteredOnly) setRequiredNetworkType(NetworkType.UNMETERED) }
            .build()
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build()
        val signature = "daily-v3:${settings.days}:${settings.hour}:${settings.chargingOnly}:${settings.unmeteredOnly}"
        manager.enqueueUniquePeriodicWork(PERIODIC, if (AutoBackupStore.scheduleKey(context) == signature) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
        AutoBackupStore.scheduleKey(context, signature)
    }
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<AutoBackupWorker>().setInputData(workDataOf("manual" to true)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP, request)
    }
}

internal object AutoBackupSchedule {
    fun isOverdue(settings: AutoBackupSettings, lastSuccess: Long, now: ZonedDateTime = ZonedDateTime.now()): Boolean =
        lastSuccess > 0 && isDue(settings, lastSuccess, now)

    fun isDue(settings: AutoBackupSettings, lastSuccess: Long, now: ZonedDateTime = ZonedDateTime.now()): Boolean =
        lastSuccess <= 0 || !now.isBefore(due(settings, lastSuccess, now))

    fun nextAttempt(settings: AutoBackupSettings, lastSuccess: Long, now: ZonedDateTime): ZonedDateTime {
        var slot = now.toLocalDate().atTime(settings.hour, 0).atZone(now.zone)
        if (!slot.isAfter(now)) slot = slot.plusDays(1)
        val due = lastSuccess.takeIf { it > 0 }?.let { due(settings, it, now) }
        return if (due != null && due.isAfter(slot)) due else slot
    }

    private fun due(settings: AutoBackupSettings, lastSuccess: Long, now: ZonedDateTime): ZonedDateTime =
        Instant.ofEpochMilli(lastSuccess).atZone(now.zone).toLocalDate().plusDays(settings.days.toLong())
            .atTime(settings.hour, 0).atZone(now.zone)
}

internal class AutoBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val manual = inputData.getBoolean("manual", false)
        val previousSuccess = AutoBackupStore.lastSuccess(context)
        try {
            val (settings, password) = AutoBackupSettingsRepository.snapshotForBackup(context, manual)
                ?: return@withContext Result.success()
            // Full archives can take longer than the ordinary background worker window.
            try {
                setForeground(createForegroundInfo())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.warn("Automatic backup foreground promotion failed: ${failure.javaClass.simpleName}")
            }
            EtaBackupOperations.exclusive(backup = true) {
                EtaBackupOperations.requireIdle(context)
                AutoBackupDocuments.save(context, settings, password)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            if (AutoBackupStore.lastSuccess(context) == previousSuccess) AutoBackupStore.status(context, "备份已中断，等待下次执行")
            throw cancelled
        } catch (failure: Exception) {
            val retry = runAttemptCount < 3
            val detail = failure.message ?: "请检查目录权限"
            AutoBackupStore.status(context, "自动备份未完成 · $detail" + if (retry) "（系统稍后重试）" else "（请处理后重新备份）")
            if (retry) Result.retry() else Result.failure()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        runCatching {
            applicationContext.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "自动备份", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(
                applicationContext,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("自动备份")
            .setContentText("正在整理应用数据")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(pending)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private companion object {
        const val CHANNEL_ID = "eta-auto-backup"
        const val NOTIFICATION_ID = 8141
    }

}

internal data class AutoBackupActivity(val running: Boolean = false, val immediatePending: Boolean = false)
