package io.github.mangi.eta.agent.cloud

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Cloud IDs are scoped to the local owner and the explicit provider account/endpoint fingerprint. */
internal class CloudDocumentResourceStore(context: Context, scopeKey: String, accountIdentity: String) {
    private val directory = File(context.noBackupFilesDir, "cloud-document-resources-v1")
    private val file = AtomicFile(File(directory, "${digest(scopeKey + "\n" + accountIdentity)}.json"))

    fun record(kind: String, id: String, metadata: JSONObject) = synchronized(lock) {
        val document = read()
        val bucket = document.optJSONObject(kind) ?: JSONObject().also { document.put(kind, it) }
        if (bucket.length() >= 1000 && !bucket.has(id)) throw CloudApiException("cloud_resource_limit", outcomeUnknown = true)
        bucket.put(id, JSONObject(metadata.toString()).put("recorded_at", System.currentTimeMillis()))
        write(document)
    }

    fun requireOwned(kind: String, id: String) = synchronized(lock) {
        val metadata = read().optJSONObject(kind)?.optJSONObject(id)
            ?: throw CloudApiException("cloud_resource_not_owned")
        if (metadata.optLong("expires_at", Long.MAX_VALUE) < System.currentTimeMillis() / 1000) {
            throw CloudApiException("cloud_resource_expired")
        }
    }

    fun get(kind: String, id: String): JSONObject = synchronized(lock) {
        requireOwned(kind, id)
        JSONObject(read().getJSONObject(kind).getJSONObject(id).toString())
    }

    fun remove(kind: String, id: String) = synchronized(lock) {
        val document = read()
        document.optJSONObject(kind)?.remove(id)
        write(document)
    }

    fun list(kind: String): JSONArray = synchronized(lock) {
        val bucket = read().optJSONObject(kind) ?: return@synchronized JSONArray()
        JSONArray(bucket.keys().asSequence().toList().mapNotNull { id -> bucket.optJSONObject(id) })
    }

    private fun read(): JSONObject {
        if (!file.baseFile.exists()) return JSONObject()
        return try {
            val bytes = file.readFully()
            if (bytes.size > 2 * 1024 * 1024) throw CloudApiException("cloud_resource_store_too_large")
            JSONObject(bytes.toString(Charsets.UTF_8))
        } catch (failure: CloudApiException) { throw failure }
        catch (_: Exception) { throw CloudApiException("cloud_resource_store_unavailable") }
    }

    private fun write(value: JSONObject) {
        check(directory.isDirectory || directory.mkdirs()) { "云文件恢复索引无法保存" }
        val stream = file.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }

    private companion object {
        val lock = Any()
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
