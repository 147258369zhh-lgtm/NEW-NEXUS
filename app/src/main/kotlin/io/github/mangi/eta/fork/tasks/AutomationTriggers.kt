package io.github.mangi.eta.fork.tasks

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import androidx.work.*
import io.github.mangi.eta.agent.skill.skillRevision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

internal object AutomationTriggers {
    @Volatile private var cachedLocation: Location? = null
    @Volatile private var cachedLocationAt: Long = 0L

    fun state(context: Context): AutomationState {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val cap = cm.getNetworkCapabilities(cm.activeNetwork)
        val audio = context.getSystemService(AudioManager::class.java)
        val headset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type in setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_HEARING_AID,
            )
        }
        return AutomationState(status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            !context.getSystemService(KeyguardManager::class.java).isDeviceLocked,
            level * 100 / scale,
            headset = headset,
            airplaneMode = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1,
        )
    }

    fun signal(context: Context, type: AutomationTrigger, key: String) {
        val work = OneTimeWorkRequestBuilder<AgentTaskWorker>().setInputData(workDataOf("eventType" to type.name, "eventKey" to key.take(200))).build()
        WorkManager.getInstance(context).enqueueUniqueWork("automation-event-${type.name}-${skillRevision(key.toByteArray()).take(20)}", ExistingWorkPolicy.KEEP, work)
    }

    suspend fun poll(context: Context, eventType: String? = null, eventKey: String = "") {
        val db = AgentTaskDatabase.get(context); val dao = db.agentTaskDao(); val triggers = db.triggerDao()
        val state = state(context)
        for (trigger in triggers.all().filter { it.enabled }) {
            val definition = dao.definition(trigger.definitionId) ?: continue
            if (!definition.enabled) continue
            val type = runCatching { AutomationTrigger.valueOf(trigger.type) }.getOrNull() ?: continue
            try {
                when (type) {
                    AutomationTrigger.FILE_CREATED, AutomationTrigger.FILE_CHANGED -> pollFiles(context, trigger, type, state)
                    AutomationTrigger.TASK_SUCCEEDED, AutomationTrigger.TASK_FAILED -> {
                        val desired = if (type == AutomationTrigger.TASK_SUCCEEDED) AgentTaskState.SUCCEEDED else AgentTaskState.FAILED
                        val completed = dao.runsForDefinition(trigger.source).filter { it.state == desired }
                            .sortedWith(compareBy<AgentTaskRunEntity> { it.finishedAt ?: 0 }.thenBy { it.id })
                        val last = completed.drop(completed.indexOfFirst { it.id == trigger.cursor } + 1).firstOrNull()
                        if (last != null && last.id != trigger.cursor) fire(context, trigger, last.id,
                            JSONObject().put("sourceTask", trigger.source).put("sourceRun", last.id), state, last.id)
                    }
                    AutomationTrigger.STATE -> {
                        val matches = AutomationSpec.parse(definition.policyJson).condition.matches(state)
                        if (!matches && trigger.cursor != "false") triggers.save(trigger.copy(cursor = "false"))
                        else if (matches && trigger.cursor != "true") fire(context, trigger, UUID.randomUUID().toString(), JSONObject(), state, "true")
                    }
                    AutomationTrigger.BATTERY_ABOVE, AutomationTrigger.BATTERY_BELOW ->
                        pollBattery(trigger, type, state, context)
                    AutomationTrigger.LOCATION_ENTER, AutomationTrigger.LOCATION_EXIT ->
                        pollLocation(trigger, type, state, context)
                    AutomationTrigger.APP_OPENED -> pollAppOpened(context, trigger, state)
                    AutomationTrigger.CHARGING, AutomationTrigger.CHARGING_STOPPED,
                    AutomationTrigger.NETWORK, AutomationTrigger.UNLOCKED,
                    AutomationTrigger.HEADSET_CONNECTED, AutomationTrigger.HEADSET_DISCONNECTED,
                    AutomationTrigger.AIRPLANE_MODE_ON, AutomationTrigger.AIRPLANE_MODE_OFF,
                    -> pollEdgeState(context, trigger, type, state, eventKey)
                    else -> if (eventType == type.name && eventKey.isNotBlank()) fire(context, trigger, eventKey, JSONObject(), state, eventKey)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val reason = failure.message?.replace(Regex("\\s+"), " ")?.take(160)
                triggers.save(trigger.copy(detail = listOfNotNull("触发检查失败，请检查目录授权或配置", reason).joinToString("：")))
            }
        }
    }

    /** Fast path used by the foreground monitor so app-open triggers do not wait for the minute scan. */
    suspend fun pollAppOpenTriggers(context: Context) {
        val db = AgentTaskDatabase.get(context)
        val triggers = db.triggerDao().all().filter { trigger ->
            trigger.enabled && trigger.type == AutomationTrigger.APP_OPENED.name
        }
        if (triggers.isEmpty()) return
        val state = state(context)
        triggers.forEach { trigger ->
            if (db.agentTaskDao().definition(trigger.definitionId)?.enabled != true) return@forEach
            try {
                pollAppOpened(context, trigger, state)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                db.triggerDao().save(
                    trigger.copy(detail = "应用活动读取失败：${failure.message?.take(120) ?: failure.javaClass.simpleName}"),
                )
            }
        }
    }

    private suspend fun pollEdgeState(
        context: Context,
        trigger: AutomationTriggerEntity,
        type: AutomationTrigger,
        state: AutomationState,
        eventKey: String,
    ) {
        val desired = when (type) {
            AutomationTrigger.CHARGING -> true
            AutomationTrigger.CHARGING_STOPPED -> false
            AutomationTrigger.NETWORK -> true
            AutomationTrigger.UNLOCKED -> true
            AutomationTrigger.HEADSET_CONNECTED -> true
            AutomationTrigger.HEADSET_DISCONNECTED -> false
            AutomationTrigger.AIRPLANE_MODE_ON -> true
            AutomationTrigger.AIRPLANE_MODE_OFF -> false
            else -> return
        }
        val current = when (type) {
            AutomationTrigger.CHARGING, AutomationTrigger.CHARGING_STOPPED -> state.charging
            AutomationTrigger.NETWORK -> state.network
            AutomationTrigger.UNLOCKED -> state.unlocked
            AutomationTrigger.HEADSET_CONNECTED, AutomationTrigger.HEADSET_DISCONNECTED -> state.headset
            AutomationTrigger.AIRPLANE_MODE_ON, AutomationTrigger.AIRPLANE_MODE_OFF -> state.airplaneMode
            else -> false
        }
        val matches = current == desired
        val previous = trigger.cursor.toBooleanStrictOrNull()
        if (previous == null) {
            AgentTaskDatabase.get(context).triggerDao().save(
                trigger.copy(cursor = matches.toString(), detail = "监听就绪"),
            )
        } else if (matches && !previous) {
            fire(
                context,
                trigger,
                eventKey.ifBlank { UUID.randomUUID().toString() },
                JSONObject()
                    .put("headset", state.headset)
                    .put("airplaneMode", state.airplaneMode),
                state,
                "true",
            )
        } else if (!matches && previous) {
            AgentTaskDatabase.get(context).triggerDao().save(
                trigger.copy(cursor = "false", detail = "等待状态变化"),
            )
        }
    }

    private suspend fun pollAppOpened(
        context: Context,
        trigger: AutomationTriggerEntity,
        state: AutomationState,
    ) {
        val dao = AgentTaskDatabase.get(context).triggerDao()
        if (!hasUsageAccess(context)) {
            dao.save(trigger.copy(detail = "等待授予使用情况访问权限"))
            return
        }
        val now = System.currentTimeMillis()
        val cursor = trigger.cursor.toLongOrNull()
        if (cursor == null) {
            dao.save(trigger.copy(cursor = now.toString(), detail = "已建立应用活动基线"))
            return
        }
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(cursor + 1, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED || event.packageName != trigger.source) continue
            fire(
                context,
                trigger,
                "usage:${event.packageName}:${event.timeStamp}:${event.className}",
                JSONObject()
                    .put("packageName", event.packageName)
                    .put("activity", event.className)
                    .put("openedAt", event.timeStamp),
                state,
                now.toString(),
            )
            val latest = dao.get(trigger.id) ?: return
            dao.save(latest.copy(cursor = now.toString(), detail = "已检测到应用打开"))
            return
        }
        dao.save(trigger.copy(cursor = now.toString(), detail = "等待打开指定应用"))
    }

    internal fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    private suspend fun pollBattery(
        trigger: AutomationTriggerEntity,
        type: AutomationTrigger,
        state: AutomationState,
        context: Context,
    ) {
        val threshold = AutomationTriggerConfig.batteryThreshold(trigger.source)
        val matches = when (type) {
            AutomationTrigger.BATTERY_ABOVE -> state.battery >= threshold
            AutomationTrigger.BATTERY_BELOW -> state.battery <= threshold
            else -> false
        }
        val previous = trigger.cursor.toBooleanStrictOrNull()
        if (previous == null) {
            AgentTaskDatabase.get(context).triggerDao().save(
                trigger.copy(cursor = matches.toString(), detail = "基线电量 ${state.battery}% · 阈值 ${threshold}%"),
            )
        } else if (matches && !previous) {
            fire(
                context,
                trigger,
                "battery:${type.name}:$state.battery:$threshold",
                JSONObject().put("battery", state.battery).put("threshold", threshold),
                state,
                "true",
            )
        } else if (!matches && previous) {
            AgentTaskDatabase.get(context).triggerDao().save(
                trigger.copy(cursor = "false", detail = "等待电量跨过 ${threshold}%（当前 ${state.battery}%）"),
            )
        }
    }

    private suspend fun pollLocation(
        trigger: AutomationTriggerEntity,
        type: AutomationTrigger,
        state: AutomationState,
        context: Context,
    ) {
        val area = LocationAutomationConfig.parse(trigger.source)
        val location = currentLocation(context)
        if (location == null) {
            AgentTaskDatabase.get(context).triggerDao().save(
                trigger.copy(detail = "等待定位；请开启位置权限与定位服务"),
            )
            return
        }
        val distance = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, area.latitude, area.longitude, distance)
        val matches = when (type) {
            AutomationTrigger.LOCATION_ENTER -> distance[0] <= area.radiusMeters
            AutomationTrigger.LOCATION_EXIT -> distance[0] > area.radiusMeters
            else -> false
        }
        val previous = trigger.cursor.toBooleanStrictOrNull()
        val detail = "距区域 ${distance[0].toInt()} 米 · 半径 ${area.radiusMeters} 米"
        if (previous == null) {
            AgentTaskDatabase.get(context).triggerDao().save(trigger.copy(cursor = matches.toString(), detail = detail))
        } else if (matches && !previous) {
            fire(
                context,
                trigger,
                "location:${type.name}:${location.time}:${location.accuracy.toInt()}",
                JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
                    .put("distanceMeters", distance[0].toInt()),
                state,
                "true",
            )
        } else if (!matches && previous) {
            AgentTaskDatabase.get(context).triggerDao().save(trigger.copy(cursor = "false", detail = detail))
        }
    }

    private suspend fun currentLocation(context: Context): Location? {
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = context.getSystemService(LocationManager::class.java)
        val recent = cachedLocation?.takeIf { System.currentTimeMillis() - cachedLocationAt <= 60_000L }
        if (recent != null) return recent
        val providers = buildList {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (isEmpty()) manager.getProviders(true).firstOrNull()?.let(::add)
        }
        for (provider in providers) {
            val fresh = withTimeoutOrNull(6_000L) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val listener = LocationListener { location ->
                        if (continuation.isActive) continuation.resume(location)
                    }
                    try {
                        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                    } catch (failure: SecurityException) {
                        continuation.resume(null)
                    } catch (failure: IllegalArgumentException) {
                        continuation.resume(null)
                    }
                }
            }
            if (fresh != null) {
                cachedLocation = fresh
                cachedLocationAt = System.currentTimeMillis()
                return fresh
            }
        }
        return manager.getProviders(true).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }?.also {
            cachedLocation = it
            cachedLocationAt = System.currentTimeMillis()
        }
    }

    private suspend fun pollFiles(context: Context, trigger: AutomationTriggerEntity, type: AutomationTrigger, state: AutomationState) {
        val db = AgentTaskDatabase.get(context); val dao = db.triggerDao()
        val tree = Uri.parse(trigger.source)
        val current = JSONObject()
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE)
        val pending = ArrayDeque<String>().apply { add(DocumentsContract.getTreeDocumentId(tree)) }
        val visited = hashSetOf<String>()
        var count = 0
        while (pending.isNotEmpty()) {
            val documentId = pending.removeFirst()
            if (!visited.add(documentId)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            context.contentResolver.query(children, columns, Bundle.EMPTY, null).use { cursor ->
                requireNotNull(cursor) { "目录不可读" }
                while (cursor.moveToNext()) {
                    require(++count <= 2000) { "目录超过 2000 项" }
                    val childId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1).orEmpty()
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(childId)
                        continue
                    }
                    if (trigger.extension.isNotBlank() && !name.endsWith(".${trigger.extension}", true)) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId)
                    current.put(uri.toString(), "${cursor.getLong(3)}:${cursor.getLong(4)}:$name")
                }
            }
        }
        if (trigger.cursor.isEmpty()) { dao.save(trigger.copy(cursor = current.toString(), detail = "已建立目录基线")); return }
        val previous = JSONObject(trigger.cursor)
        // Advance each successfully queued file separately; busy/conditions never discard a pending file.
        for (uri in current.keys().asSequence().toList()) {
            val signature = current.getString(uri)
            if (previous.has(uri) && (type == AutomationTrigger.FILE_CREATED || previous.optString(uri) == signature)) continue
            if (!fire(context, trigger.copy(cursor = previous.toString()), "$uri:$signature", JSONObject().put("fileUri", uri), state,
                    JSONObject(previous.toString()).put(uri, signature).toString())) return
            previous.put(uri, signature)
        }
        previous.keys().asSequence().toList().filterNot(current::has).forEach(previous::remove)
        val latest = dao.get(trigger.id) ?: return
        dao.save(latest.copy(cursor = previous.toString()))
    }

    suspend fun fire(context: Context, snapshot: AutomationTriggerEntity, eventKey: String, payload: JSONObject,
        state: AutomationState, nextCursor: String): Boolean {
        val db = AgentTaskDatabase.get(context)
        return db.withTransaction {
            val trigger = db.triggerDao().get(snapshot.id) ?: return@withTransaction false
            val definition = db.agentTaskDao().definition(trigger.definitionId) ?: return@withTransaction false
            val now = System.currentTimeMillis()
            if (!trigger.enabled || !definition.enabled || trigger.cursor != snapshot.cursor || trigger.cursor == nextCursor) return@withTransaction false
            if (now - trigger.lastFiredAt < trigger.cooldownSeconds * 1000L || db.agentTaskDao().activeCount(definition.id) > 0) return@withTransaction false
            val spec = AutomationSpec.parse(definition.policyJson)
            if (db.agentTaskDao().runsForDefinition(definition.id).count { it.createdAt > now - 86400000 && it.idempotencyKey.startsWith("event:") } >= spec.maxEventRunsPerDay) {
                db.triggerDao().save(trigger.copy(detail = "已达到每日事件运行上限")); return@withTransaction false
            }
            if (!spec.condition.matches(state)) {
                db.triggerDao().save(trigger.copy(detail = "等待设备条件满足")); return@withTransaction false
            }
            val runId = UUID.randomUUID().toString()
            val inserted = db.agentTaskDao().insertRunIfAbsent(AgentTaskRunEntity(id = runId, definitionId = definition.id,
                scheduleId = null, scheduledFor = now, attempt = 1, idempotencyKey = "event:${trigger.id}:${skillRevision(eventKey.toByteArray())}",
                state = AgentTaskState.QUEUED, createdAt = now, updatedAt = now))
            // Duplicate delivery advances the cursor too, without creating another execution.
            db.triggerDao().save(trigger.copy(cursor = nextCursor, lastFiredAt = now, detail = "已触发"))
            if (inserted >= 0) db.agentTaskDao().appendEvent(runId, "TRIGGER", payload.put("type", trigger.type).toString(), now)
            true
        }
    }

    fun requireAcyclic(triggers: List<AutomationTriggerEntity>) {
        val edges = triggers.filter { it.type in setOf(AutomationTrigger.TASK_SUCCEEDED.name, AutomationTrigger.TASK_FAILED.name) }
            .groupBy { it.definitionId }.mapValues { (_, ts) -> ts.map { it.source } }
        fun visit(id: String, path: Set<String>) {
            require(id !in path) { "任务依赖形成循环" }
            edges[id].orEmpty().forEach { visit(it, path + id) }
        }
        edges.keys.forEach { visit(it, emptySet()) }
    }
}

internal class AutomationSystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AgentTaskWorkScheduler.ensureScheduled(context)
            AutomationMonitorService.restoreIfEnabled(context)
            AutomationTriggers.signal(context, AutomationTrigger.BOOT, "boot:${android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", 0)}")
        }
    }
}
