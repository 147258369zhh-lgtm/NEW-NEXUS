package io.github.mangi.eta.data.repository

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small, append-safe index for files produced by a run.  The file itself remains the source of
 * truth; this index only lets the UI find a verified artifact after the run has gone away.
 */
internal data class WorkspaceArtifact(
    val id: String,
    val runId: String,
    val path: String,
    val label: String,
    val kind: String = "file",
    val status: String = STATUS_VERIFIED,
    val createdAt: Long,
    val verifiedAt: Long? = null,
    val projectId: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_VERIFIED = "verified"
        const val STATUS_FAILED = "failed"
    }
}

internal class WorkspaceArtifactStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "workspace-artifacts.json")

    @Synchronized
    fun list(limit: Int = 40): List<WorkspaceArtifact> = read()
        .sortedWith(compareByDescending<WorkspaceArtifact> { it.createdAt }.thenBy { it.id })
        .take(limit.coerceIn(1, 200))

    @Synchronized
    fun record(
        runId: String,
        path: String,
        label: String = path.substringAfterLast('/').ifBlank { path },
        kind: String = "file",
        status: String = WorkspaceArtifact.STATUS_PENDING,
        now: Long = System.currentTimeMillis(),
        projectId: String? = null,
    ): WorkspaceArtifact {
        val normalized = normalizePath(path)
        require(runId.isNotBlank() && runId.length <= 256) { "ARTIFACT_RUN_INVALID" }
        require(kind.isNotBlank() && kind.length <= 64) { "ARTIFACT_KIND_INVALID" }
        require(status in setOf(
            WorkspaceArtifact.STATUS_PENDING,
            WorkspaceArtifact.STATUS_VERIFIED,
            WorkspaceArtifact.STATUS_FAILED,
        )) { "ARTIFACT_STATUS_INVALID" }
        val artifact = WorkspaceArtifact(
            id = "artifact_${UUID.randomUUID()}",
            runId = runId,
            path = normalized,
            label = label.trim().take(255).ifBlank { normalized.substringAfterLast('/') },
            kind = kind.trim(),
            status = status,
            createdAt = now,
            verifiedAt = now.takeIf { status == WorkspaceArtifact.STATUS_VERIFIED },
            projectId = projectId?.trim()?.takeIf { it.isNotEmpty() },
        )
        write((read() + artifact).takeLast(MAX_RECORDS))
        return artifact
    }

    @Synchronized
    fun updateStatus(id: String, status: String, now: Long = System.currentTimeMillis()): Boolean {
        require(status in setOf(
            WorkspaceArtifact.STATUS_PENDING,
            WorkspaceArtifact.STATUS_VERIFIED,
            WorkspaceArtifact.STATUS_FAILED,
        )) { "ARTIFACT_STATUS_INVALID" }
        var changed = false
        val updated = read().map { item ->
            if (item.id != id) item else {
                changed = true
                item.copy(status = status, verifiedAt = now.takeIf { status == WorkspaceArtifact.STATUS_VERIFIED })
            }
        }
        if (changed) write(updated)
        return changed
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val next = read().filterNot { it.id == id }
        if (next.size == read().size) return false
        write(next)
        return true
    }

    private fun read(): List<WorkspaceArtifact> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val path = item.optString("path").takeIf(String::isNotBlank) ?: continue
                val runId = item.optString("runId").takeIf(String::isNotBlank) ?: continue
                add(WorkspaceArtifact(
                    id = item.optString("id").ifBlank { "artifact_${index}" },
                    runId = runId,
                    path = normalizePath(path),
                    label = item.optString("label").ifBlank { path.substringAfterLast('/') },
                    kind = item.optString("kind").ifBlank { "file" },
                    status = item.optString("status").ifBlank { WorkspaceArtifact.STATUS_PENDING },
                    createdAt = item.optLong("createdAt"),
                    verifiedAt = item.optLong("verifiedAt").takeIf { it > 0 },
                    projectId = item.optString("projectId").takeIf { it.isNotBlank() },
                ))
            }
        }
    }.getOrDefault(emptyList())

    private fun write(items: List<WorkspaceArtifact>) {
        val parent = file.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) error("ARTIFACT_INDEX_UNAVAILABLE")
        val temporary = File(parent, "${file.name}.tmp")
        val array = JSONArray()
        items.takeLast(MAX_RECORDS).forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("runId", item.runId)
                put("path", item.path)
                put("label", item.label)
                put("kind", item.kind)
                put("status", item.status)
                put("createdAt", item.createdAt)
                item.verifiedAt?.let { put("verifiedAt", it) }
                item.projectId?.let { put("projectId", it) }
            })
        }
        temporary.writeText(array.toString(), Charsets.UTF_8)
        runCatching {
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse { throw IllegalStateException("ARTIFACT_INDEX_WRITE_FAILED", it) }
    }

    private fun normalizePath(path: String): String {
        val normalized = path.replace('\\', '/').trim().trim('/')
        require(normalized.isNotEmpty() && normalized.length <= 1_024) { "ARTIFACT_PATH_INVALID" }
        require(normalized.split('/').none { it.isEmpty() || it == "." || it == ".." || '\u0000' in it }) {
            "ARTIFACT_PATH_INVALID"
        }
        return normalized
    }

    private companion object { const val MAX_RECORDS = 200 }
}
