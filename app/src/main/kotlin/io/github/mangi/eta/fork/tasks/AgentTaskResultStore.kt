package io.github.mangi.eta.fork.tasks

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** Task-owned receipt: ACK must never leave only a dangling Runtime result id. */
internal class AgentTaskResultStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "workflow-state/automation-results")

    fun save(taskRunId: String, result: AgentRuntimeWire.RunResult) = synchronized(LOCK) {
        require(taskRunId.isNotBlank() && result.runId.isNotBlank() && result.contextSnapshotRef.isBlank())
        check(root.mkdirs() || root.isDirectory) { "无法创建自动任务结果目录" }
        val file = file(result.runId)
        val output = file.startWrite()
        try {
            val receipt = JSONObject().put("taskRunId", taskRunId)
                .put("result", Json.encodeToString(result)).toString()
            output.write(receipt.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Throwable) {
            file.failWrite(output)
            throw failure
        }
    }

    fun read(runtimeRunId: String, taskRunId: String? = null): AgentRuntimeWire.RunResult? = synchronized(LOCK) {
        val file = file(runtimeRunId)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val receipt = JSONObject(file.openRead().bufferedReader().use { it.readText() })
        require(taskRunId == null || receipt.getString("taskRunId") == taskRunId) { "任务结果归属不匹配" }
        return Json.decodeFromString<AgentRuntimeWire.RunResult>(receipt.getString("result"))
            .also { require(it.runId == runtimeRunId && it.contextSnapshotRef.isBlank()) }
    }

    /** Bounded retention for already acknowledged task receipts; unresolved receipts stay until ACK. */
    fun prune(
        now: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = MAX_RECEIPT_AGE_MILLIS,
        maxFiles: Int = MAX_RECEIPT_FILES,
    ): Int = synchronized(LOCK) {
        require(maxAgeMillis > 0 && maxFiles > 0)
        val files = root.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            .orEmpty()
            .sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
        var removed = 0
        files.forEachIndexed { index, file ->
            val expired = file.lastModified() <= now - maxAgeMillis
            if (expired || index >= maxFiles) {
                AtomicFile(file).delete()
                removed++
            }
        }
        removed
    }

    private fun file(id: String): AtomicFile {
        val key = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return AtomicFile(File(root, "$key.json"))
    }

    private companion object {
        val LOCK = Any()
        const val MAX_RECEIPT_AGE_MILLIS = 90L * 24L * 60L * 60L * 1_000L
        const val MAX_RECEIPT_FILES = 256
    }
}
