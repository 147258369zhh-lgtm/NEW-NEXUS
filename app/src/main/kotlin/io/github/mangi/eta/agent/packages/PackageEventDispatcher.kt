package io.github.mangi.eta.agent.packages

import android.content.Context
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.RunState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal data class PackageEventDelivery(val record: CapabilityPackage, val tool: PackageTool, val arguments: JSONObject,
    val subject: ConversationSubject?, val runId: String = "package-event-${UUID.randomUUID()}")

/** Persist before acknowledgement. In-flight recovery observes the original Run; it never repeats an unknown callback. */
internal class PackageEventDispatcher(
    private val journal: PackageEventJournal,
    private val records: () -> List<CapabilityPackage>,
    private val deliver: suspend (PackageEventDelivery) -> Unit,
    scope: CoroutineScope,
    private val recover: suspend (PackageQueuedEvent) -> PackageEventStatus? = { null },
) : AutoCloseable {
    private val queue = Channel<Unit>(Channel.CONFLATED)
    private val submitted = AtomicLong(1)
    private val processed = AtomicLong(0)
    private val lastFailure = java.util.concurrent.atomic.AtomicReference<Exception?>(null)
    private val lock = Any()
    private var closed = false
    private val job = scope.launch {
        for (signal in queue) {
            val generation = submitted.get()
            try { drain(); lastFailure.set(null) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { lastFailure.set(failure); AndroidAgentLogger.warn("Package event journal unavailable: ${failure.javaClass.simpleName}") }
            finally { processed.set(generation) }
        }
    }
    init { queue.trySend(Unit); job.invokeOnCompletion { journal.close() } }
    fun submit(kind: String, name: String, payload: JSONObject, subject: ConversationSubject? = null): Boolean = synchronized(lock) {
        require(kind in PackageExtensionKinds.automatic && name.length in 1..96)
        require(payload.toString().toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "PACKAGE_EVENT_PAYLOAD_LIMIT" }
        if (closed) return@synchronized false
        val deliveries = select(records(), kind, name, payload, subject)
        if (deliveries.isEmpty()) return@synchronized false
        val accepted = journal.enqueue(deliveries)
        if (accepted) { submitted.incrementAndGet(); queue.trySend(Unit) }
        else AndroidAgentLogger.warn("Package event inbox full; notification was not accepted")
        accepted
    }
    internal fun refresh() { submitted.incrementAndGet(); queue.trySend(Unit) }
    internal suspend fun awaitIdle() {
        val target = submitted.get(); while (processed.get() < target && job.isActive) delay(5)
        lastFailure.get()?.let { throw it }
    }
    internal suspend fun shutdown() { close(); job.join() }
    private suspend fun drain() {
        for (event in journal.unsettled()) {
            currentCoroutineContext().ensureActive()
            if (event.receipt.status != PackageEventStatus.PENDING) {
                val outcome = recover(event)
                if (outcome != null) journal.finish(event.receipt.runId, outcome)
                else if (event.receipt.status == PackageEventStatus.RUNNING) journal.finish(event.receipt.runId, PackageEventStatus.UNKNOWN)
                continue
            }
            val record = records().singleOrNull { it.id == event.receipt.packageId && it.digest == event.digest && it.enabled &&
                event.tool in it.tools && event.tool.callbackKey?.substringBefore('/') in it.enabledExtensions }
            if (record == null) { journal.finish(event.receipt.runId, PackageEventStatus.REVOKED); continue }
            if (!journal.claim(event.receipt.runId)) continue
            try {
                withTimeout(30_000) { deliver(PackageEventDelivery(record, event.tool, event.arguments, event.subject, event.receipt.runId)) }
                journal.finish(event.receipt.runId, PackageEventStatus.SUCCEEDED)
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                journal.finish(event.receipt.runId, PackageEventStatus.UNKNOWN)
            } catch (failure: Exception) {
                journal.finish(event.receipt.runId, recover(event.copy(receipt = event.receipt.copy(status = PackageEventStatus.UNKNOWN))) ?: PackageEventStatus.UNKNOWN)
                AndroidAgentLogger.warn("Package event delivery unsettled: ${failure.javaClass.simpleName}")
            }
        }
    }
    override fun close() = synchronized(lock) { closed = true; queue.close(); job.cancel() }

    companion object {
        internal fun select(records: List<CapabilityPackage>, kind: String, name: String, payload: JSONObject, subject: ConversationSubject?): List<PackageEventDelivery> =
            records.filter { it.enabled && kind in it.enabledExtensions && it.mainEntry != null }.flatMap { record ->
                val rows = JSONArray(record.registrations)
                (0 until rows.length()).mapNotNull { index ->
                    val row = rows.getJSONObject(index)
                    if (row.optString("kind") != kind || kind == "AppLifecycleHook" && row.optString("event") != name) return@mapNotNull null
                    val callback = PackageExtensionKinds.callback(row) ?: return@mapNotNull null
                    val tool = record.tools.singleOrNull { it.callbackKey == callback } ?: return@mapNotNull null
                    PackageEventDelivery(record, tool, JSONObject().put("eventName", name).put("eventPayload", JSONObject(payload.toString())), subject)
                }
            }.also { require(it.size <= 128) { "PACKAGE_EVENT_SUBSCRIBER_LIMIT" } }
    }
}

internal object PackageApplicationEvents {
    private val lock = Any()
    @Volatile private var dispatcher: PackageEventDispatcher? = null
    @Volatile private var applicationContext: Context? = null
    internal fun initialize(context: Context, delivery: (suspend (PackageEventDelivery) -> Unit)? = null) = synchronized(lock) {
        val app = context.applicationContext
        if (applicationContext === app) return@synchronized
        dispatcher?.close()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val service = PackageApplicationService(app)
        dispatcher = PackageEventDispatcher(PackageEventJournal(app), { CapabilityPackageStore(app).list() }, delivery ?: { event ->
            val controller = AgentRunController()
            try {
                val result = runInterruptible { service.runActionReviewed(event.record, event.tool, event.arguments, controller, event.subject, event.runId) }
                check(result.ok) { "PACKAGE_EVENT_NOT_COMPLETED" }
            } finally { controller.cancel() }
            Unit
        }, scope, recover = { event -> reconcile(app, event) })
        applicationContext = app
    }
    internal suspend fun reconcile(context: Context, event: PackageQueuedEvent): PackageEventStatus? {
        fun receipt(): PackageEventStatus? = NexusExecutionStore(context).use { store ->
            val id = NexusRunMapper.canonicalRunId(event.receipt.runId)
            val run = store.find(id) ?: return@use null
            val callback = store.items(id).singleOrNull { it.itemId.value == NexusExecutionIds.tool(event.receipt.runId, 1, "package-direct") }
            when {
                callback?.state == RunState.SUCCEEDED || run.state == RunState.SUCCEEDED -> PackageEventStatus.SUCCEEDED
                callback?.state == RunState.FAILED -> PackageEventStatus.FAILED
                run.state in setOf(RunState.FAILED, RunState.CANCELLED, RunState.INTERRUPTED) && callback == null -> PackageEventStatus.FAILED
                else -> null
            }
        }
        receipt()?.let { return it }
        if (event.receipt.status != PackageEventStatus.RUNNING) return null
        withTimeoutOrNull(15_000) {
            runInterruptible { AgentRuntimeClient(context, AndroidAgentLogger).attachRun(event.receipt.runId, onEvent = {}) }
        }
        return receipt()
    }
    internal suspend fun awaitIdle() { dispatcher?.awaitIdle() }
    internal fun refresh() { dispatcher?.refresh() }
    internal fun closeForTests() = synchronized(lock) { dispatcher?.close(); dispatcher = null; applicationContext = null }
    fun emit(context: Context, kind: String, name: String, payload: JSONObject, subject: ConversationSubject? = null) {
        if (applicationContext !== context.applicationContext) return
        try {
            if (dispatcher?.submit(kind, name, payload, subject) == true) PackageEventDrainWorker.schedule(context)
        } catch (failure: Exception) { AndroidAgentLogger.warn("Package event could not be persisted: ${failure.javaClass.simpleName}") }
    }
}
