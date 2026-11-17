package io.github.mangi.eta.agent.cloud

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject

/** Persisted before returning task IDs, scoped to the trusted subject and project, never model claims. */
internal class CloudMediaTaskStore(private val context: CloudToolContext) {
    private val identity = context.controller.executionIdentity ?: throw CloudApiException("run_context_required")
    val scope = "${context.scopeKey}\u0000${identity.projectId.orEmpty()}"
    private val directory = File(context.androidContext.noBackupFilesDir, "cloud-media-tasks-v1/${digest(scope)}")

    init { check(directory.isDirectory || directory.mkdirs()) { "媒体任务记录无法保存" } }

    fun create(taskId: String, kind: String, endpoint: String, account: String, model: String, response: JSONObject): JSONObject {
        require(kind in setOf("image", "video"))
        check(directory.isDirectory || directory.mkdirs()) { "媒体任务记录无法保存" }
        if ((directory.listFiles { _, name -> name.endsWith(".json") }?.size ?: 0) >= 2000) throw CloudApiException("media_task_store_full")
        val now = System.currentTimeMillis()
        val record = JSONObject().put("task_id", taskId).put("kind", kind).put("model", model)
            .put("scope", scope).put("run_id", identity.runId.value).put("endpoint", endpoint)
            .put("account", account)
            .put("created_at", now).put("expires_at", now + 24L * 60 * 60 * 1000)
            .put("task_status", response.optJSONObject("output")?.optString("task_status", "PENDING") ?: "PENDING")
        save(record)
        return record
    }

    fun load(taskId: String): JSONObject {
        val file = file(taskId)
        if (!file.baseFile.exists()) throw CloudApiException("media_task_not_found_in_current_subject")
        val record = try { JSONObject(file.readFully().toString(Charsets.UTF_8)) }
        catch (_: Exception) { throw CloudApiException("media_task_record_unreadable") }
        if (record.optString("scope") != scope || record.optString("task_id") != taskId) throw CloudApiException("media_task_scope_mismatch")
        return record
    }

    fun save(record: JSONObject) {
        if (record.optString("scope") != scope) throw CloudApiException("media_task_scope_mismatch")
        val bytes = record.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 256 * 1024) throw CloudApiException("media_task_record_too_large")
        val target = file(record.getString("task_id"))
        val stream = target.startWrite()
        try { stream.write(bytes); target.finishWrite(stream) }
        catch (failure: Exception) { target.failWrite(stream); throw failure }
    }

    fun list(): JSONArray = JSONArray().apply {
        directory.listFiles { _, name -> name.endsWith(".json") }.orEmpty()
            .sortedByDescending(File::lastModified).take(200).forEach { file ->
                val record = runCatching { JSONObject(AtomicFile(file).readFully().toString(Charsets.UTF_8)) }.getOrNull()
                if (record != null && record.optString("scope") == scope) put(summary(record))
            }
    }

    suspend fun <T> locked(taskId: String, block: suspend () -> T): T {
        val mutex = locks.getOrPut("$scope\u0000$taskId") { Mutex() }
        mutex.lock()
        try { return block() } finally { mutex.unlock() }
    }

    private fun file(taskId: String): AtomicFile {
        require(taskId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) { "task_id 无效" }
        return AtomicFile(File(directory, "${digest(taskId)}.json"))
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()

        fun summary(record: JSONObject): JSONObject = JSONObject().put("task_id", record.getString("task_id"))
            .put("kind", record.getString("kind")).put("model", record.getString("model"))
            .put("task_status", record.optString("task_status", "PENDING"))
            .put("created_at", record.getLong("created_at")).put("expires_at", record.getLong("expires_at"))
            .put("files_saved", record.optJSONArray("files")?.length() ?: 0)

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
