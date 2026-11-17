package io.github.mangi.eta.ui.screens.capability

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.dsh.DshProtocol
import io.github.mangi.eta.agent.dsh.DshViewDocument
import io.github.mangi.eta.agent.dsh.DshViewNode
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class DshViewAction(val id: String, val value: JSONObject, val userGesture: Boolean, val tappedAtNanos: Long)

/** One mounted panel owns one serial queue. Reconnection never replays an abandoned write. */
internal class DshPanelActions(parent: CoroutineScope, private val state: DshFormState,
    private val invoke: suspend (DshViewAction) -> Boolean, private val now: () -> Long = System::nanoTime) : AutoCloseable {
    private data class Entry(val action: String, val value: JSONObject, val tap: Long?, val submission: DshFormSubmission?,
        val bind: String?, val form: String?, var epoch: Long = 0, val result: CompletableDeferred<Boolean> = CompletableDeferred())
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val queue = ArrayDeque<Entry>()
    private val changes = linkedMapOf<String, Job>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var online = false
    private var generation: Any? = null
    private var epoch = 0L
    private var inFlight: Deferred<Boolean>? = null
    private var nextChangeAt = 0L
    private val formOwners = linkedMapOf<String, Entry>()
    var pending by mutableStateOf<String?>(null)
        private set
    var forms by mutableStateOf<Set<String>>(emptySet())
        private set

    init {
        scope.launch {
            for (ignored in signal) while (queue.isNotEmpty()) {
                if (!online) { abandon(); break }
                var entry = queue.removeFirst()
                if (entry.bind != null && now() < nextChangeAt) {
                    val immediate = queue.indexOfFirst { it.bind == null }
                    if (immediate >= 0) { val button = queue.removeAt(immediate); queue.addFirst(entry); entry = button }
                    else delay(TimeUnit.NANOSECONDS.toMillis(nextChangeAt - now()).coerceAtLeast(1))
                }
                if (!online || entry.epoch != epoch || entry.result.isCompleted || entry.submission?.fields?.any { (path, receipt) ->
                    state.document.fields[path]?.source?.toString() != receipt.schema
                } == true) {
                    entry.result.complete(false)
                    entry.form?.let { if (formOwners[it] === entry) { formOwners.remove(it); forms = formOwners.keys.toSet() } }
                    continue
                }
                pending = entry.action
                entry.submission?.let(state::started)
                try {
                    if (entry.bind != null) nextChangeAt = now() + TimeUnit.MILLISECONDS.toNanos(250)
                    val gesture = entry.tap?.let { now() - it in 0..TimeUnit.SECONDS.toNanos(3) } == true
                    val task = scope.async { invoke(DshViewAction(entry.action, entry.value, gesture, entry.tap ?: now())) }.also { inFlight = it }
                    val success = task.await()
                    entry.submission?.let { state.settled(it, success) }; entry.result.complete(success)
                } catch (cancelled: CancellationException) {
                    entry.submission?.let { state.settled(it, false) }; entry.result.complete(false)
                    currentCoroutineContext().ensureActive()
                } catch (_: Exception) { entry.submission?.let { state.settled(it, false) }; state.failed("未保存，请重试"); entry.result.complete(false) }
                finally { pending = null; inFlight = null; entry.form?.let { if (formOwners[it] === entry) { formOwners.remove(it); forms = formOwners.keys.toSet() } } }
            }
        }
    }

    fun connection(online: Boolean, generation: Any?) {
        if (this.generation != generation || !online) { epoch++; abandon(); inFlight?.cancel() }
        this.generation = generation; this.online = online
    }
    fun action(id: String, value: JSONObject): Deferred<Boolean> = enqueue(Entry(id, JSONObject(value.toString()), now(), null, null, null))
    fun submit(node: DshViewNode, data: Any?): Deferred<Boolean> {
        if (node.path in forms) return CompletableDeferred(false)
        val submission = state.submission(form = node.path) ?: return CompletableDeferred(false)
        state.document.fields.filterValues { it.form == node.path }.keys.forEach { bind ->
            changes.remove(bind)?.cancel()
            val abandoned = queue.filter { it.bind == bind }; queue.removeAll(abandoned.toSet()); abandoned.forEach { it.result.complete(false) }
        }
        val action = definition(node.source.opt("submit"), data) ?: run { state.failed("表单缺少提交动作"); return CompletableDeferred(false) }
        val value = merge(action.second, submission.value)
        val result = enqueue(Entry(action.first, value, now(), submission, null, node.path))
        return result
    }
    fun changed(node: DshViewNode) {
        val bind = node.source.optString("bind")
        changes.remove(bind)?.cancel()
        if (!online || !node.source.has("onChange")) return
        changes[bind] = scope.launch {
            delay(250)
            if (state.document.fields[bind]?.source?.toString() != node.source.toString()) return@launch
            val submission = state.submission(only = bind) ?: return@launch
            val action = definition(node.source.opt("onChange"), state.effectiveData()) ?: return@launch
            val previous = queue.filter { it.bind == bind }; queue.removeAll(previous.toSet()); previous.forEach { it.result.complete(false) }
            enqueue(Entry(action.first, merge(action.second, submission.value), null, submission, bind, null))
        }
    }
    private fun enqueue(entry: Entry): Deferred<Boolean> {
        if (!online || !DshProtocol.actionId.matches(entry.action) || entry.value.toString().toByteArray().size > 4096 || queue.size >= 120) {
            state.failed(if (online) "操作未排入队列，请检查内容或稍后重试" else "连接中断，内容尚未保存")
            entry.result.complete(false)
        } else { entry.epoch = epoch; entry.form?.let { formOwners[it] = entry; forms = formOwners.keys.toSet() }; queue.addLast(entry); signal.trySend(Unit) }
        return entry.result
    }
    private fun abandon() {
        changes.values.forEach(Job::cancel); changes.clear()
        queue.forEach { it.result.complete(false) }; queue.clear(); formOwners.clear(); forms = emptySet()
    }
    override fun close() { abandon(); inFlight?.cancel(); scope.cancel(); signal.close() }
    companion object {
        fun definition(raw: Any?, data: Any?): Pair<String, JSONObject>? = when (val source = DshViewDocument.value(raw, data)) {
            is String -> source.takeIf(DshProtocol.actionId::matches)?.let { it to JSONObject() }
            is JSONObject -> source.optString("id").takeIf(DshProtocol.actionId::matches)?.let { id ->
                val args = DshViewDocument.value(source.opt("args"), data) as? JSONObject ?: JSONObject()
                if (args.toString().toByteArray().size <= 1024) id to JSONObject(args.toString()) else null
            }
            else -> null
        }
        private fun merge(args: JSONObject, values: JSONObject): JSONObject = JSONObject(args.toString()).apply { values.keys().forEach { put(it, values.get(it)) } }
    }
}
