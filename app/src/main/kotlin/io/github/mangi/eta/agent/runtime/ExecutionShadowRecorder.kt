package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.zhh_lgtm.nexus.kernel.api.NexusProduct
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * Non-blocking shadow execution ledger. A bounded queue and one writer prevent diagnostics from
 * stalling the Agent loop; write failures are contained to the ledger file.
 */
internal class ExecutionShadowRecorder constructor(
    private val output: File,
    private val metadata: JSONObject,
) : AutoCloseable {
    private val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
    private val closed = AtomicBoolean(false)
    private val sequence = AtomicLong(0)
    private val dropped = AtomicLong(0)
    private val writer = Thread(::writeLoop, "nexus-execution-ledger").apply {
        isDaemon = true
        start()
    }

    init {
        enqueue(record("run.started", JSONObject()))
    }

    fun record(event: AgentEvent) {
        if (closed.get()) return
        enqueue(record("agent.event", JSONObject()
            .put("event_type", event.javaClass.simpleName)
            .put("dropped_before", dropped.getAndSet(0))))
    }

    fun finish(result: AgentRuntimeWire.RunResult) {
        if (closed.get()) return
        enqueue(record("run.terminal", JSONObject()
            .put("ok", result.ok)
            .put("error_type", result.error.orEmpty().take(256))
            .put("content_chars", result.content.length)
            .put("transcript_messages", result.transcript.size)
            .put("dropped_before", dropped.getAndSet(0))))
        shutdown()
    }

    private fun record(type: String, payload: JSONObject): String = JSONObject()
        .put("schema_version", NexusProduct.DATA_SCHEMA_VERSION)
        .put("sequence", sequence.getAndIncrement())
        .put("created_at", System.currentTimeMillis())
        .put("type", type)
        .put("run", JSONObject(metadata.toString()))
        .put("payload", payload)
        .toString()

    private fun enqueue(line: String) {
        if (!queue.offer(line)) dropped.incrementAndGet()
    }

    private fun writeLoop() {
        output.parentFile?.mkdirs()
        FileOutputStream(output, true).buffered().use { stream ->
            while (true) {
                val line = queue.take()
                if (line == STOP) break
                try {
                    stream.write(line.toByteArray(Charsets.UTF_8))
                    stream.write('\n'.code)
                    stream.flush()
                } catch (_: Throwable) {
                    // Keep consuming so a single malformed line cannot deadlock the producer.
                }
            }
        }
    }

    override fun close() {
        shutdown()
    }

    private fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        queue.offer(STOP)
    }

    companion object {
        private const val QUEUE_CAPACITY = 2_048
        private const val STOP = "__NEXUS_LEDGER_STOP__"

        fun create(context: Context, request: AgentRuntimeWire.RunRequest): ExecutionShadowRecorder? = runCatching {
            val app = context.applicationContext
            val conversationId = AgentConversationIdentity.fromHandoff(request.handoff, request.runId)
            val source = request.handoff?.source?.takeIf(String::isNotBlank) ?: "ui"
            val directory = File(app.filesDir, "execution-ledger")
            prune(directory)
            val safeRunId = request.runId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val metadata = JSONObject()
                .put("run_id", request.runId)
                .put("session_id", conversationId)
                .put("source", source)
                .put("operation", request.operation)
                .put("host", "native-android")
            ExecutionShadowRecorder(File(directory, "$safeRunId.jsonl"), metadata)
        }.onFailure { failure ->
            AndroidAgentLogger.warn("Execution ledger initialization failed: type=${failure.safeLogType()}")
        }.getOrNull()

        fun readTimeline(context: Context, runId: String, afterSequence: Long = -1L): List<JSONObject> {
            val directory = File(context.applicationContext.filesDir, "execution-ledger")
            val safeRunId = runId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(directory, "$safeRunId.jsonl")
            if (!file.isFile) return emptyList()
            return file.useLines { lines ->
                lines.mapNotNull { line ->
                    runCatching { JSONObject(line) }
                        .getOrNull()
                        ?.takeIf { it.optLong("sequence", -1L) > afterSequence }
                }.toList()
            }
        }

        private fun prune(directory: File) {
            if (!directory.isDirectory) return
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)
            val files = directory.listFiles { file -> file.isFile && file.extension == "jsonl" }
                .orEmpty()
                .sortedByDescending(File::lastModified)
            files.forEachIndexed { index, file ->
                if (index >= MAX_RETAINED_RUNS || file.lastModified() < cutoff) {
                    runCatching { file.delete() }
                }
            }
        }

        private const val MAX_RETAINED_RUNS = 200
        private const val RETENTION_DAYS = 30L
    }
}
