package io.github.mangi.eta.agent.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.zhh_lgtm.nexus.application.api.ResourcePressure
import io.github.zhh_lgtm.nexus.application.api.ResourcePriorityPolicy
import io.github.zhh_lgtm.nexus.application.api.WorkloadType
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

/** One generation slot across the three existing text engines; audio keeps its separate owners. */
internal class LocalInferenceScheduler(private val pressure: () -> ResourcePressure = { ResourcePressure() }) {
    private data class Ticket(val controller: AgentRunController, val workload: WorkloadType, val order: Long, val modelId: String?)
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = mutableListOf<Ticket>()
    private var active: Ticket? = null
    private var sequence = 0L
    private val stopping = mutableMapOf<String, Int>()

    fun <T> execute(controller: AgentRunController, workload: WorkloadType, modelId: String? = null, block: () -> T): T {
        val ticket = lock.withLock {
            check(modelId == null || modelId !in stopping) { "LOCAL_MODEL_STOPPING" }
            Ticket(controller, workload, ++sequence, modelId).also { queue += it; changed.signalAll() }
        }
        val cancellation = controller.register { signal() }
        val pause = controller.observePause { signal() }
        try {
            var acquired = false
            while (!acquired) {
                var preempt: AgentRunController? = null
                lock.withLock {
                    controller.throwIfStopped()
                    if (!ResourcePriorityPolicy.mayContinue(workload, pressure())) throw yieldFailure()
                    val next = queue.filterNot { it.controller.isPaused || it.controller.isCancelled }
                        .minWithOrNull(compareBy<Ticket> { it.workload.ordinal }.thenBy { it.order })
                    if (active == null && next === ticket) {
                        queue.remove(ticket); active = ticket; acquired = true
                    } else {
                        val running = active
                        if (running != null && !running.controller.isCancelled &&
                            running.workload in setOf(WorkloadType.NIGHTLY_CONSOLIDATION, WorkloadType.MODEL_WARMUP) &&
                            ResourcePriorityPolicy.shouldYield(running.workload, workload)) preempt = running.controller
                        else changed.await(250, TimeUnit.MILLISECONDS)
                    }
                }
                preempt?.abort(yieldFailure())
            }
            controller.throwIfCancelled()
            return block()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            controller.throwIfStopped()
            throw AgentRunCancelledException()
        } finally {
            cancellation.close(); pause.close()
            lock.withLock { queue.remove(ticket); if (active === ticket) active = null; changed.signalAll() }
        }
    }

    fun pressureChanged() {
        val owner = lock.withLock { active?.takeIf { !ResourcePriorityPolicy.mayContinue(it.workload, pressure()) }?.controller }
        owner?.abort(yieldFailure()); signal()
    }

    fun stopModel(modelId: String): AutoCloseable {
        require(modelId.isNotBlank())
        val owners = lock.withLock {
            stopping[modelId] = stopping.getOrDefault(modelId, 0) + 1
            (queue + listOfNotNull(active)).filter { it.modelId == modelId }.map { it.controller }.distinct()
        }
        owners.forEach { it.cancel() }
        signal()
        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) lock.withLock {
                val count = stopping.getValue(modelId) - 1
                if (count == 0) stopping.remove(modelId) else stopping[modelId] = count
                changed.signalAll()
            }
        }
    }

    fun snapshot(): JSONObject = lock.withLock {
        JSONObject().put("activeRunId", active?.controller?.executionIdentity?.runId?.value)
            .put("activeModelId", active?.modelId)
            .put("activeWorkload", active?.workload?.name).put("queued", JSONArray(queue.map {
                JSONObject().put("runId", it.controller.executionIdentity?.runId?.value).put("workload", it.workload.name).put("modelId", it.modelId)
            }))
    }

    private fun signal() = lock.withLock { changed.signalAll() }
    private fun yieldFailure() = AgentModelFailure("RESOURCE_YIELD", false, "后台整理已让位，等待设备空闲后继续。")
}

internal object LocalInferenceScheduling {
    @Volatile private var context: Context? = null
    private val scheduler = LocalInferenceScheduler(::pressure)
    private var monitor: LocalResourcePressureMonitor? = null
    @Synchronized fun initialize(value: Context) {
        val app = value.applicationContext
        if (context === app) return
        monitor?.close()
        context = app
        monitor = LocalResourcePressureMonitor(app, ::pressureChanged)
    }
    fun snapshot(): JSONObject = scheduler.snapshot()
    fun pressureChanged() = scheduler.pressureChanged()

    fun <T> execute(controller: AgentRunController, modelId: String? = null, block: () -> T): T = scheduler.execute(controller, workload(controller), modelId, block)
    fun stopModel(modelId: String): AutoCloseable = scheduler.stopModel(modelId)

    suspend fun <T> warmUp(modelId: String, automatic: Boolean, block: suspend () -> T): T = coroutineScope {
        val controller = AgentRunController()
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { controller.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                val parentContext = currentCoroutineContext()
                scheduler.execute(controller, if (automatic) WorkloadType.MODEL_WARMUP else WorkloadType.USER_TASK, modelId) {
                    val owner = Job(parentContext.job)
                    val binding = controller.register { owner.cancel() }
                    try { runBlocking(owner) { block() }.also { controller.throwIfStopped() } }
                    catch (failure: CancellationException) { controller.throwIfStopped(); throw failure }
                    finally { binding.close(); owner.cancel() }
                }
            }
        } catch (failure: AgentRunCancelledException) {
            throw CancellationException("Local model warmup cancelled", failure)
        } finally { cancellation.cancel() }
    }

    private fun workload(controller: AgentRunController) = when (controller.executionIdentity?.source) {
        RunSource.NOTE_CONSOLIDATION -> WorkloadType.NIGHTLY_CONSOLIDATION
        RunSource.CHAT, null -> WorkloadType.FOREGROUND_CHAT
        else -> WorkloadType.USER_TASK
    }

    private fun pressure(): ResourcePressure {
        val context = context ?: return ResourcePressure()
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        val power = context.getSystemService(PowerManager::class.java)
        return ResourcePressure(memory.lowMemory, power?.isPowerSaveMode == true,
            (power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_SEVERE)
    }
}
