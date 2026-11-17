package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.workflow.WorkflowRevisionStore
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.UserInputPart
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class RunInputRequest(
    val runId: String,
    val source: RunSource,
    val part: UserInputPart,
    val conversationId: String? = null,
    val targetId: String? = null,
    val actorId: String? = null,
    val recovered: Boolean = false,
    val payloadRef: String? = null,
)

/** Questions are committed before display and answers before waking the original tool call. */
internal object RunUserInput {
    const val TOOL = "request_user_input"
    private val lock = Any()
    private val requests = linkedMapOf<String, Pending>()
    private val recoveredRequests = linkedMapOf<String, RunInputRequest>()
    private val mutablePending = MutableStateFlow<List<RunInputRequest>>(emptyList())
    val pending = mutablePending.asStateFlow()

    /** Rebuilds questions that were committed before the process was reclaimed. */
    suspend fun restore(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val markers = RecoveryMarkers(appContext)
        val rows = NexusExecutionStore(appContext).use { it.unresolvedUserInputs(MAX_RECOVERED_REQUESTS) }
        val revisions = WorkflowRevisionStore(appContext)
        val restored = rows.mapNotNull { row -> runCatching {
            val body = revisions.readCommitted("input", row.payloadRef)
            val requestId = body.optString("request_id").takeIf { it.isNotBlank() }
                ?: return@runCatching null
            val deadline = body.optLong("deadline_at", 0L)
            if (deadline > 0L && System.currentTimeMillis() > deadline) {
                markers.markConsumed(requestId, RunInputPhase.TIMED_OUT)
                return@runCatching null
            }
            val submitted = RecoveredRunInputStore(appContext).use { it.consumed(requestId) }
            if (submitted || markers.isConsumed(requestId) || body.optString("run_id") != row.runId) {
                return@runCatching null
            }
            val conversationId = body.optString("conversation_id").takeIf(String::isNotBlank)
            val question = body.optString("question").trim().takeIf { it.length in 1..2000 }
                ?: return@runCatching null
            val optionsJson = body.optJSONArray("options") ?: JSONArray()
            val options = (0 until optionsJson.length()).mapNotNull { index ->
                optionsJson.optString(index).trim().takeIf { it.length in 1..200 }
            }
            val source = body.optString("recovery_surface").ifBlank { body.optString("source") }.let { name ->
                RunSource.entries.firstOrNull { it.name == name } ?: RunSource.CHAT
            }
            val targetId = body.optString("target_id").takeIf(String::isNotBlank)
            if (source !in setOf(RunSource.CHAT, RunSource.GOAL, RunSource.MEETING_HOST, RunSource.MEETING_PARTICIPANT)) {
                return@runCatching null
            }
            if (source in setOf(RunSource.MEETING_HOST, RunSource.MEETING_PARTICIPANT) && targetId == null) {
                return@runCatching null
            }
            if (source in setOf(RunSource.CHAT, RunSource.GOAL) && conversationId == null) return@runCatching null
            RunInputRequest(
                runId = row.runId,
                source = source,
                part = UserInputPart(requestId, requestId, question, options,
                    body.optBoolean("allow_free_text", true), RunInputForms.parse(body), deadline.takeIf { it > 0L }),
                conversationId = conversationId,
                targetId = targetId,
                actorId = body.optString("actor_id").takeIf(String::isNotBlank),
                recovered = true,
                payloadRef = row.payloadRef,
            )
        }.getOrNull() }
        synchronized(lock) {
            (restored + io.github.mangi.eta.agent.mcp.McpContinuationRecovery.additionalPending(appContext))
                .distinctBy { it.part.requestId }.forEach { request ->
                if (request.part.requestId !in requests) {
                    recoveredRequests.putIfAbsent(request.part.requestId, request)
                }
            }
            publish()
        }
    }

    fun answer(id: String, answer: String): Boolean = synchronized(lock) { requests[id] }?.let {
        val text = answer.trim()
        if (it.request.part.deadlineAtMillis?.let { deadline -> System.currentTimeMillis() > deadline } == true) {
            it.complete(RunInputPhase.TIMED_OUT)
            false
        } else if (it.request.part.questions.isNotEmpty()) {
            if (runCatching { RunInputForms.answer(it.request.part.questions, text) }.isFailure) false
            else it.complete(RunInputPhase.ANSWERED, text)
        } else if (text.isEmpty() || text.length > 4000 || !it.request.part.allowFreeText && text !in it.request.part.options) false
        else it.complete(RunInputPhase.ANSWERED, text)
    } ?: false

    fun cancel(id: String): Boolean = synchronized(lock) { requests[id] }?.complete(RunInputPhase.CANCELLED) ?: false
    fun decline(id: String): Boolean = synchronized(lock) { requests[id] }?.complete(RunInputPhase.DECLINED) ?: false

    fun execute(context: Context, arguments: JSONObject, timeoutMillis: Long = 5 * 60_000L): JSONObject {
        val controller = RuntimeInvocationScope.controller ?: error("RUN_CONTEXT_REQUIRED")
        controller.throwIfCancelled()
        val questions = RunInputForms.parse(arguments)
        val question = arguments.optString("question").trim().ifBlank { questions.firstOrNull()?.title.orEmpty() }
            .also { require(it.length in 1..2000) { "INPUT_QUESTION_INVALID" } }
        val array = arguments.optJSONArray("options") ?: JSONArray()
        require(array.length() <= 8) { "INPUT_OPTIONS_INVALID" }
        val options = (0 until array.length()).map { array.getString(it).trim().also { text ->
            require(text.length in 1..200) { "INPUT_OPTIONS_INVALID" }
        } }.also { require(it.distinct().size == it.size) { "INPUT_OPTIONS_INVALID" } }
        val freeText = if (arguments.has("allow_free_text")) arguments.getBoolean("allow_free_text") else true
        require(freeText || options.isNotEmpty() || questions.isNotEmpty()) { "INPUT_OPTIONS_REQUIRED" }
        require(timeoutMillis in 1..30 * 60_000L)
        if (controller.isRemoteA2AInbound) {
            // The remote peer owns the answer. Stop the run with a safe summary instead of
            // publishing a local prompt, committing a private ledger entry, or waiting.
            val pause = AgentRunPauseException(
                AgentRuntimeWire.RunPause(
                    kind = AgentRuntimeWire.RunPause.KIND_INPUT_REQUIRED,
                    question = question,
                    options = options,
                    allowFreeText = freeText,
                ),
            )
            controller.abort(pause)
            throw pause
        }
        val owner = controller.executionIdentity ?: error("INPUT_LEDGER_UNAVAILABLE")
        val id = "input-${UUID.randomUUID()}"
        val deadline = System.currentTimeMillis() + timeoutMillis
        val part = UserInputPart(id, id, question, options, freeText, questions, deadline)
        val revisions = WorkflowRevisionStore(context)
        val body = JSONObject().put("request_id", id).put("run_id", owner.runId.value).put("question", question)
            .put("options", JSONArray(options)).put("allow_free_text", freeText)
            .put("source", owner.source.name)
            .put("schema_version", 2).put("deadline_at", deadline).put("questions", RunInputForms.encode(questions))
        RuntimeInvocationScope.toolInvocation?.let { invocation ->
            body.put("tool_call_id", invocation.callId).put("tool_name", invocation.toolName)
                .put("provider_id", invocation.providerId).put("model", invocation.model)
            if (invocation.messagesJson.length <= 2 * 1024 * 1024) body.put("continuation_messages", invocation.messagesJson)
            else body.put("continuation_unavailable", "context_too_large")
        }
        // Only trusted in-process MCP scope may attach a protocol continuation; model arguments are ignored.
        io.github.mangi.eta.agent.mcp.McpElicitationScope.attach(body)
        controller.conversationId?.let { body.put("conversation_id", it) }
        controller.executionTargetId?.let { body.put("target_id", it) }
        controller.recoverySurface?.let { body.put("recovery_surface", it.name) }
        controller.recoveryActorId?.let { body.put("actor_id", it) }
        val reference = revisions.save(owner.runId.value, "input", body)
        io.github.mangi.eta.agent.mcp.McpElicitationScope.committedQuestion(body, reference)
        controller.recordUserInput(RunInputChange(id, RunInputPhase.REQUESTED, reference))
        val entry = Pending(
            RunInputRequest(
                runId = owner.runId.value,
                source = controller.recoverySurface ?: owner.source,
                part = part,
                conversationId = controller.conversationId,
                targetId = controller.executionTargetId,
                actorId = controller.recoveryActorId,
                payloadRef = reference,
            ),
            controller,
            revisions,
            body,
        )
        synchronized(lock) { requests[id] = entry; publish() }
        var binding: AgentRunController.ResourceBinding? = null
        try {
            binding = controller.register { entry.complete(RunInputPhase.CANCELLED) }
            val started = System.nanoTime()
            while (!entry.latch.await(100, TimeUnit.MILLISECONDS)) {
                controller.throwIfStopped()
                if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(timeoutMillis)) entry.complete(RunInputPhase.TIMED_OUT)
            }
            entry.failure?.let { throw it }
            controller.throwIfCancelled()
            return checkNotNull(entry.result)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentRunCancelledException()
        } finally {
            try { entry.complete(RunInputPhase.CANCELLED) } finally {
                binding?.close()
                synchronized(lock) { requests.remove(id); publish() }
            }
        }
    }

    fun finishRecovered(context: Context, id: String, phase: RunInputPhase): Boolean {
        require(phase in setOf(RunInputPhase.ANSWERED, RunInputPhase.DECLINED, RunInputPhase.CANCELLED, RunInputPhase.TIMED_OUT))
        val recovered = synchronized(lock) { recoveredRequests[id] } ?: return false
        RecoveredRunInputStore(context).use { store ->
            if (phase == RunInputPhase.ANSWERED) {
                if (!store.consumed(id)) return false
            } else store.consumeTerminal(context, recovered, phase)
        }
        // The outbox is authoritative; legacy markers remain for older histories only.
        runCatching { RecoveryMarkers(context.applicationContext).markConsumed(recovered.part.requestId, phase) }
        synchronized(lock) {
            recoveredRequests.remove(id)
            publish()
        }
        return true
    }

    private fun publish() {
        mutablePending.value = requests.values.filterNot { it.completing.get() }.map { it.request } +
            recoveredRequests.values
    }

    private class Pending(val request: RunInputRequest, val controller: AgentRunController,
        val revisions: WorkflowRevisionStore, val body: JSONObject) {
        val completing = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        @Volatile var failure: RuntimeException? = null
        @Volatile var result: JSONObject? = null

        fun complete(phase: RunInputPhase, answer: String? = null): Boolean {
            require(phase != RunInputPhase.REQUESTED)
            if (!completing.compareAndSet(false, true)) return false
            try {
                val outcome = JSONObject().put("ok", phase == RunInputPhase.ANSWERED).put("request_id", request.part.requestId)
                    .put("status", phase.name.lowercase())
                if (answer != null && request.part.questions.isNotEmpty()) {
                    val answers = RunInputForms.answer(request.part.questions, answer)
                    outcome.put("answers", answers).put("answer", RunInputForms.summary(request.part.questions, answers))
                } else if (answer != null) outcome.put("answer", answer)
                else outcome.put("code", "INPUT_${phase.name}").put("message", if (phase == RunInputPhase.TIMED_OUT) "等待回答超时" else "用户取消了补充输入")
                val reference = revisions.save(request.runId, "input", JSONObject(body.toString()).put("result", outcome))
                controller.recordUserInput(RunInputChange(request.part.requestId, phase, reference))
                result = outcome
                return true
            } catch (cause: Exception) {
                val stopped = IllegalStateException("INPUT_RESULT_UNRECORDED", cause)
                failure = stopped
                controller.abort(stopped)
                throw stopped
            } finally {
                synchronized(lock) { publish() }
                latch.countDown()
            }
        }
    }

    private class RecoveryMarkers(context: Context) {
        private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

        fun isConsumed(requestId: String): Boolean = preferences.contains(requestId)

        fun markConsumed(requestId: String, phase: RunInputPhase) {
            synchronized(preferences) {
                val values = preferences.all.entries
                    .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
                    .sortedBy { it.second.substringBefore(':').toLongOrNull() ?: 0L }
                val editor = preferences.edit().putString(requestId, "${System.currentTimeMillis()}:${phase.name}")
                values.take((values.size - MAX_RECOVERY_MARKERS + 1).coerceAtLeast(0)).forEach { (key, _) ->
                    editor.remove(key)
                }
                check(editor.commit()) { "INPUT_RECOVERY_MARKER_UNAVAILABLE" }
            }
        }

        private companion object {
            const val PREFERENCES = "run_input_recovery"
            const val MAX_RECOVERY_MARKERS = 256
        }
    }

    private const val MAX_RECOVERED_REQUESTS = 32
}
