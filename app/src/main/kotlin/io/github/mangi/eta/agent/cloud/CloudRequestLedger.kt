package io.github.mangi.eta.agent.cloud

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * A durable reservation is committed BEFORE an effectful request. An interrupted request is never
 * silently retried, including after process death. Records intentionally stay out of app backups.
 */
internal class CloudRequestLedger(context: Context, private val scopeKey: String = "local") {
    private val directory = File(context.noBackupFilesDir, "cloud-request-ledger-v1")

    suspend fun execute(
        requestId: String,
        operation: String,
        fingerprint: String,
        block: suspend () -> JSONObject,
    ): JSONObject {
        require(requestId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{7,127}"))) {
            "需提供 8–128 位 request_id；恢复同一请求须复用原 ID"
        }
        val recordKey = digest("$scopeKey\n$requestId")
        val mutex = locks.getOrPut(recordKey) { Mutex() }
        mutex.lock()
        try {
            check(directory.isDirectory || directory.mkdirs()) { "云请求恢复记录无法保存" }
            // File lock also protects against another app-owned process submitting this request.
            RandomAccessFile(File(directory, "$recordKey.lock"), "rw").use { lockFile ->
                lockFile.channel.lock().use {
                    val file = AtomicFile(File(directory, "$recordKey.json"))
                    if (file.baseFile.exists()) {
                        val previous = try { JSONObject(file.readFully().toString(Charsets.UTF_8)) }
                        catch (_: Exception) { throw CloudApiException("request_record_unreadable", outcomeUnknown = true) }
                        if (previous.optString("fingerprint") != fingerprint || previous.optString("operation") != operation) {
                            throw CloudApiException("request_id_conflict")
                        }
                        previous.optJSONObject("result")?.let { return JSONObject(it.toString()).put("reused_request", true) }
                        return JSONObject().put("ok", false).put("error", "request_outcome_unknown")
                            .put("request_id", requestId).put("outcome_unknown", true).put("retry_safe", false)
                            .put("message", "先查询原任务或在供应商控制台核对；不要再次提交收费任务")
                    }
                    if ((directory.listFiles { _, name -> name.endsWith(".json") }?.size ?: 0) >= 5000) {
                        throw CloudApiException("request_ledger_full")
                    }
                    val record = JSONObject().put("operation", operation).put("fingerprint", fingerprint)
                        .put("created_at", System.currentTimeMillis()).put("state", "submitting")
                    write(file, record)
                    try {
                        val result = block().put("request_id", requestId)
                        record.put("state", "complete").put("result", result)
                        write(file, record)
                        return result
                    } catch (failure: Exception) {
                        // Keep reservation on cancellation and unclassified failures. No provider
                        // response text or credential is copied into a persisted error.
                        if (failure is CloudApiException && !failure.outcomeUnknown) {
                            record.put("state", "rejected").put("result", failure.toJson().put("request_id", requestId))
                            write(file, record)
                        }
                        throw failure
                    }
                }
            }
        } finally { mutex.unlock() }
    }

    private fun write(file: AtomicFile, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 10 * 1024 * 1024) throw CloudApiException("request_record_too_large", outcomeUnknown = true)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }

    companion object {
        private val locks = ConcurrentHashMap<String, Mutex>()

        fun fingerprint(args: JSONObject): String = digest(canonical(args))

        private fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString("\n", "{", "}") {
                JSONObject.quote(it) + ":" + canonical(value.opt(it))
            }
            is JSONArray -> (0 until value.length()).joinToString("\n", "[", "]") { canonical(value.opt(it)) }
            null, JSONObject.NULL -> "null"
            is String -> JSONObject.quote(value)
            is Boolean -> value.toString()
            is Number -> JSONObject.numberToString(value)
            else -> JSONObject.quote(value.toString())
        }

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
