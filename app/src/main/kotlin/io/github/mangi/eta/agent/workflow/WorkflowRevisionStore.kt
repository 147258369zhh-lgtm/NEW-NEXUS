package io.github.mangi.eta.agent.workflow

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONObject

/** Immutable private snapshots; execution events contain only the content-addressed reference. */
internal class WorkflowRevisionStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "workflow-state/revisions")

    fun save(scope: String, kind: String, body: JSONObject): String {
        require(kind in kinds)
        val payload = body.toString()
        require(payload.length <= MAX_CHARS) { "WORKFLOW_REVISION_TOO_LARGE" }
        val scopeHash = hash(scope)
        val digest = hash(payload)
        val destination = target(scopeHash, kind, digest)
        if (!destination.exists()) {
            check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs()) { "WORKFLOW_REVISION_STORAGE_UNAVAILABLE" }
            val temporary = File(destination.parentFile, ".${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(payload.toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
        check(hash(destination.readText(Charsets.UTF_8)) == digest) { "WORKFLOW_REVISION_CORRUPT" }
        return "nexus-workflow://$scopeHash/$kind/$digest"
    }

    fun read(scope: String, kind: String, reference: String): JSONObject {
        require(kind in kinds)
        val uri = URI(reference)
        require(uri.scheme == "nexus-workflow" && uri.rawAuthority == hash(scope) && uri.rawQuery == null && uri.rawFragment == null) {
            "WORKFLOW_REVISION_SCOPE_MISMATCH"
        }
        return readCommitted(kind, reference)
    }

    /** Only the local execution reader may dereference a revision already linked by a committed fact. */
    internal fun readCommitted(kind: String, reference: String): JSONObject {
        require(kind in kinds)
        val uri = URI(reference)
        require(uri.scheme == "nexus-workflow" && digestPattern.matches(uri.rawAuthority.orEmpty()) &&
            uri.rawQuery == null && uri.rawFragment == null) { "WORKFLOW_REVISION_INVALID" }
        val segments = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
        require(segments.size == 2 && segments[0] == kind && digestPattern.matches(segments[1])) { "WORKFLOW_REVISION_INVALID" }
        val source = target(uri.rawAuthority, kind, segments[1])
        check(source.isFile && source.length() <= MAX_CHARS.toLong() * 4) { "WORKFLOW_REVISION_UNAVAILABLE" }
        val payload = source.readText(Charsets.UTF_8)
        check(hash(payload) == segments[1]) { "WORKFLOW_REVISION_CORRUPT" }
        return JSONObject(payload)
    }

    private fun target(scopeHash: String, kind: String, digest: String): File {
        require(digestPattern.matches(scopeHash) && kind in kinds && digestPattern.matches(digest))
        return File(root, "$scopeHash/$kind/$digest.json")
    }

    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        val kinds = setOf("goal", "plan", "notepad", "input")
        val digestPattern = Regex("[a-f0-9]{64}")
        const val MAX_CHARS = 4 * 1024 * 1024
    }
}
