package io.github.mangi.eta.agent.capability

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.core.NexusUiContract
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SkillUpdateState(
    val checkedAt: Long = 0L,
    val updates: List<SkillMarketItem> = emptyList(),
    val failedSourceCount: Int = 0,
    val error: String? = null,
)

internal class SkillUpdateStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "capability-center/skill-updates.json")

    @Synchronized
    fun read(): SkillUpdateState {
        if (!file.isFile) return SkillUpdateState()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.optInt("version") == VERSION)
            val updates = root.optJSONArray("updates") ?: JSONArray()
            SkillUpdateState(
                checkedAt = root.optLong("checkedAt", 0L).coerceAtLeast(0L),
                updates = buildList {
                    for (index in 0 until updates.length()) {
                        updates.optJSONObject(index)?.let(::parseItem)?.let(::add)
                    }
                }.distinctBy(SkillMarketItem::key),
                failedSourceCount = root.optInt("failedSourceCount", 0)
                    .coerceIn(0, MAX_SOURCES),
                error = root.optString("error").trim().take(500).takeIf(String::isNotBlank),
            )
        }.getOrDefault(SkillUpdateState())
    }

    @Synchronized
    fun write(state: SkillUpdateState) {
        val root = JSONObject()
            .put("version", VERSION)
            .put("checkedAt", state.checkedAt.coerceAtLeast(0L))
            .put("failedSourceCount", state.failedSourceCount.coerceIn(0, MAX_SOURCES))
            .put("error", state.error?.take(500) ?: JSONObject.NULL)
            .put("updates", JSONArray().also { values ->
                state.updates.take(MAX_UPDATES).forEach { values.put(encodeItem(it)) }
            })
        val parent = requireNotNull(file.parentFile) { "Skill 更新记录目录无效" }
        check(parent.mkdirs() || parent.isDirectory) { "无法创建 Skill 更新记录目录" }
        val temporary = File.createTempFile("eta-skill-updates-", ".tmp", parent)
        try {
            temporary.writeText(root.toString(), Charsets.UTF_8)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun encodeItem(item: SkillMarketItem): JSONObject = JSONObject()
        .put("sourceId", item.sourceId)
        .put("sourceName", item.sourceName)
        .put("sourceType", item.sourceType)
        .put("trust", item.trust)
        .put("skillId", item.skillId)
        .put("displayName", item.displayName)
        .put("description", item.description)
        .put("version", item.version ?: JSONObject.NULL)
        .put("publisher", item.publisher ?: JSONObject.NULL)
        .put("repository", item.repository ?: JSONObject.NULL)
        .put("ref", item.ref ?: JSONObject.NULL)
        .put("path", item.path ?: JSONObject.NULL)
        .put("archiveUrl", item.archiveUrl ?: JSONObject.NULL)
        .put("archiveSha256", item.archiveSha256 ?: JSONObject.NULL)
        .put("tags", JSONArray(item.tags))
        .put("usage", item.usage ?: JSONObject.NULL)
        .put("dependencies", JSONArray(item.dependencies))
        .put("permissions", JSONArray(item.permissions))
        .put("releaseNotes", item.releaseNotes ?: JSONObject.NULL)

    private fun parseItem(value: JSONObject): SkillMarketItem? = runCatching {
        val sourceId = value.optString("sourceId").trim()
        val sourceName = value.optString("sourceName").trim()
        val sourceType = value.optString("sourceType").trim()
        val trust = value.optString("trust").trim()
        val skillId = value.optString("skillId").trim()
        val displayName = value.optString("displayName").trim()
        require(sourceId.isNotEmpty() && sourceName.isNotEmpty())
        require(sourceType in setOf(SkillSourceType.GITHUB_REPOSITORY, SkillSourceType.HTTP_CATALOG))
        require(trust in setOf(SkillSourceTrust.OFFICIAL, SkillSourceTrust.CURATED, SkillSourceTrust.COMMUNITY, SkillSourceTrust.CUSTOM))
        require(SKILL_ID.matches(skillId) && displayName.length in 1..120)
        SkillMarketItem(
            sourceId = sourceId.take(128),
            sourceName = sourceName.take(80),
            sourceType = sourceType,
            trust = trust,
            skillId = skillId,
            displayName = displayName,
            description = value.optString("description").take(2_048),
            version = value.optionalString("version")?.take(64),
            publisher = value.optionalString("publisher")?.take(160),
            repository = value.optionalString("repository")?.take(300),
            ref = value.optionalString("ref")?.take(128),
            path = value.optionalString("path")?.take(1_000),
            archiveUrl = value.optionalString("archiveUrl")?.take(2_048),
            archiveSha256 = value.optionalString("archiveSha256")?.takeIf(ARCHIVE_SHA::matches),
            tags = value.stringList("tags").take(16),
            usage = value.optionalString("usage")?.take(8_000),
            dependencies = value.stringList("dependencies").take(32),
            permissions = value.stringList("permissions").take(32),
            releaseNotes = value.optionalString("releaseNotes")?.take(4_000),
        )
    }.getOrNull()

    private fun JSONObject.optionalString(key: String): String? =
        optString(key).trim().takeIf { it.isNotEmpty() && it != "null" }

    private fun JSONObject.stringList(key: String): List<String> {
        val values = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                values.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private companion object {
        const val VERSION = 1
        const val MAX_UPDATES = 200
        const val MAX_SOURCES = 32
        val SKILL_ID = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
        val ARCHIVE_SHA = Regex("[a-fA-F0-9]{64}")
    }
}

internal class SkillUpdateService(context: Context) {
    private val appContext = context.applicationContext
    private val store = SkillUpdateStore(appContext)

    fun lastState(): SkillUpdateState = store.read()

    suspend fun check(notify: Boolean = false): SkillUpdateState = withContext(Dispatchers.IO) {
        val marketService = SkillMarketService(appContext)
        try {
            val installed = SkillRuntime.createIndexService(appContext)
                .listSkillsForManagement(forceRefresh = true)
                .filter(SkillIndexEntry::installed)
            val market = marketService.search("")
            val updates = market.items
                .filter { SkillUpdatePolicy.hasAvailableUpdate(it, installed) }
                .distinctBy { it.skillId }
            val state = SkillUpdateState(
                checkedAt = System.currentTimeMillis(),
                updates = updates,
                failedSourceCount = market.statuses.count { !it.succeeded },
            )
            store.write(state)
            if (notify && updates.isNotEmpty()) SkillUpdateNotifications.show(appContext, updates)
            state
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            val previous = store.read()
            val state = previous.copy(
                checkedAt = System.currentTimeMillis(),
                error = failure.message?.lineSequence()?.firstOrNull()?.trim()?.take(500)
                    ?: "Skill 更新检查失败",
            )
            store.write(state)
            state
        } finally {
            marketService.close()
        }
    }
}

internal object SkillUpdateSettingsStore {
    private const val FILE = "capability_skill_updates"
    private const val KEY_ENABLED = "automatic"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        SkillUpdateScheduler.sync(context)
    }
}

internal object SkillUpdateScheduler {
    private const val PERIODIC = "eta-skill-update-check"
    private const val MANUAL = "eta-skill-update-check-now"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun sync(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!SkillUpdateSettingsStore.enabled(context)) {
            manager.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<SkillUpdateWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(6, TimeUnit.HOURS)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SkillUpdateWorker>()
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            MANUAL,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

internal class SkillUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            SkillUpdateService(applicationContext).check(notify = true)
            Result.success()
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}

private object SkillUpdateNotifications {
    private const val CHANNEL = "skill-updates"
    private const val NOTIFICATION_ID = 0x45544101

    fun show(context: Context, updates: List<SkillMarketItem>) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Skill 更新", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val intent = PendingIntent.getActivity(
                context,
                0,
                NexusUiContract.launchIntent(context).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val names = updates.take(2).joinToString(" · ") { it.displayName }
            val suffix = if (updates.size > 2) " 等 ${updates.size} 个" else ""
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("发现 ${updates.size} 个 Skill 更新")
                .setContentText(names + suffix)
                .setContentIntent(intent)
                .setAutoCancel(true)
                .build()
            if (manager.areNotificationsEnabled()) manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
