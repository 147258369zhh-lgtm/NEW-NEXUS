package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    val subjectType: String? = null,
    val subjectId: String? = null,
    val location: String = "workspace",
    val mediaType: String? = null,
    val sizeBytes: Long? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_VERIFIED = "verified"
        const val STATUS_FAILED = "failed"
    }
}

internal class WorkspaceArtifactStore(context: Context) {
    private val filesDir = context.applicationContext.filesDir
    private val file = File(filesDir, "workspace-artifacts.json")

    fun list(limit: Int = 40): List<WorkspaceArtifact> = synchronized(lock) { read()
        .sortedWith(compareByDescending<WorkspaceArtifact> { it.createdAt }.thenBy { it.id })
        .take(limit.coerceIn(1, MAX_RECORDS)) }

    fun find(id: String): WorkspaceArtifact? = synchronized(lock) { read().firstOrNull { it.id == id } }
    fun forSubject(type: String, id: String): List<WorkspaceArtifact> = list(MAX_RECORDS).filter { it.subjectType == type && it.subjectId == id }
    fun forProject(id: String): List<WorkspaceArtifact> = list(MAX_RECORDS).filter { it.projectId == id }

    fun changes(): StateFlow<Long> = changeSignal

    fun record(
        runId: String,
        path: String,
        label: String = path.substringAfterLast('/').ifBlank { path },
        kind: String = "file",
        status: String = WorkspaceArtifact.STATUS_PENDING,
        now: Long = System.currentTimeMillis(),
        projectId: String? = null,
        id: String = "artifact_${UUID.randomUUID()}",
        subjectType: String? = null,
        subjectId: String? = null,
        location: String = "workspace",
        mediaType: String? = null,
        sizeBytes: Long? = null,
        verifiedAt: Long? = now.takeIf { status == WorkspaceArtifact.STATUS_VERIFIED },
    ): WorkspaceArtifact = synchronized(lock) {
        val normalized = normalizePath(path, location)
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}"))) { "ARTIFACT_ID_INVALID" }
        require((subjectType == null) == (subjectId == null)) { "ARTIFACT_SUBJECT_INVALID" }
        require(subjectType == null || subjectType in setOf("conversation", "meeting", "task")) { "ARTIFACT_SUBJECT_INVALID" }
        require(sizeBytes == null || sizeBytes >= 0)
        require(runId.isNotBlank() && runId.length <= 256) { "ARTIFACT_RUN_INVALID" }
        require(kind.isNotBlank() && kind.length <= 64) { "ARTIFACT_KIND_INVALID" }
        require(status in setOf(
            WorkspaceArtifact.STATUS_PENDING,
            WorkspaceArtifact.STATUS_VERIFIED,
            WorkspaceArtifact.STATUS_FAILED,
        )) { "ARTIFACT_STATUS_INVALID" }
        val artifact = withActualWorkspaceSize(WorkspaceArtifact(
            id = id,
            runId = runId,
            path = normalized,
            label = label.trim().take(255).ifBlank { normalized.substringAfterLast('/') },
            kind = kind.trim(),
            status = status,
            createdAt = now,
            verifiedAt = verifiedAt,
            projectId = projectId?.trim()?.takeIf { it.isNotEmpty() },
            subjectType = subjectType, subjectId = subjectId, location = location, mediaType = mediaType, sizeBytes = sizeBytes,
        ))
        val history = read()
        val existing = history.firstOrNull { it.id == id }
        require(existing == null || existing.runId == runId && existing.path == normalized && existing.location == location &&
            existing.subjectType == subjectType && existing.subjectId == subjectId && existing.projectId == artifact.projectId) {
            "ARTIFACT_ID_CONFLICT"
        }
        WorkspaceArtifactCapacityPolicy.requireAdmission(
            existing = history,
            incoming = artifact,
            usableBytes = filesDir.usableSpace,
        )
        write(history.filterNot { it.id == id } + artifact)
        artifact
    }

    fun updateStatus(id: String, status: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        require(status in setOf(
            WorkspaceArtifact.STATUS_PENDING,
            WorkspaceArtifact.STATUS_VERIFIED,
            WorkspaceArtifact.STATUS_FAILED,
        )) { "ARTIFACT_STATUS_INVALID" }
        var changed = false
        val history = read()
        val updated = history.map { item ->
            if (item.id != id) item else {
                changed = true
                withActualWorkspaceSize(item.copy(
                    status = status,
                    verifiedAt = now.takeIf { status == WorkspaceArtifact.STATUS_VERIFIED },
                ))
            }
        }
        if (changed) {
            updated.firstOrNull { it.id == id }?.let { candidate ->
                WorkspaceArtifactCapacityPolicy.requireAdmission(history, candidate, filesDir.usableSpace)
            }
            write(updated)
        }
        changed
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val next = read().filterNot { it.id == id }
        if (next.size == read().size) return false
        write(next)
        true
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
                    path = normalizePath(path, item.optString("location", "workspace")),
                    label = item.optString("label").ifBlank { path.substringAfterLast('/') },
                    kind = item.optString("kind").ifBlank { "file" },
                    status = item.optString("status").ifBlank { WorkspaceArtifact.STATUS_PENDING },
                    createdAt = item.optLong("createdAt"),
                    verifiedAt = item.optLong("verifiedAt").takeIf { it > 0 },
                    projectId = item.optString("projectId").takeIf { it.isNotBlank() },
                    subjectType = item.optString("subjectType").takeIf(String::isNotBlank),
                    subjectId = item.optString("subjectId").takeIf(String::isNotBlank),
                    location = item.optString("location", "workspace"),
                    mediaType = item.optString("mediaType").takeIf(String::isNotBlank),
                    sizeBytes = if (item.isNull("sizeBytes")) null else item.getLong("sizeBytes"),
                ))
            }
        }
    }.getOrElse { throw IllegalStateException("ARTIFACT_INDEX_INVALID", it) }

    private fun write(items: List<WorkspaceArtifact>) {
        val parent = file.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) error("ARTIFACT_INDEX_UNAVAILABLE")
        val temporary = File(parent, "${file.name}.tmp")
        val array = JSONArray()
        items.forEach { item ->
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
                put("subjectType", item.subjectType); put("subjectId", item.subjectId); put("location", item.location)
                put("mediaType", item.mediaType); put("sizeBytes", item.sizeBytes)
            })
        }
        FileOutputStream(temporary).use { output -> output.write(array.toString().toByteArray(Charsets.UTF_8)); output.fd.sync() }
        runCatching {
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse { throw IllegalStateException("ARTIFACT_INDEX_WRITE_FAILED", it) }
        changeSignal.value += 1
    }

    private fun normalizePath(path: String, location: String = "workspace"): String {
        require(location in setOf("workspace", "device", "content")) { "ARTIFACT_LOCATION_INVALID" }
        if (location == "content") return path.trim().also {
            require(it.startsWith("content://") && it.length <= 4096 && '\u0000' !in it) { "ARTIFACT_URI_INVALID" }
        }
        val portable = path.replace('\\', '/').trim()
        require(location != "device" || portable.startsWith('/')) { "ARTIFACT_DEVICE_PATH_INVALID" }
        val normalized = if (location == "workspace") portable.trim('/') else portable.trimEnd('/')
        require(normalized.isNotEmpty() && normalized.length <= 1_024) { "ARTIFACT_PATH_INVALID" }
        require(normalized.trimStart('/').split('/').none { it.isEmpty() || it == "." || it == ".." || '\u0000' in it }) {
            "ARTIFACT_PATH_INVALID"
        }
        return normalized
    }

    private fun withActualWorkspaceSize(artifact: WorkspaceArtifact): WorkspaceArtifact {
        if (artifact.location != "workspace") return artifact
        val root = TerminalPrivateStorage.workspace(filesDir).canonicalFile
        val target = runCatching { File(root, artifact.path).canonicalFile }.getOrNull() ?: return artifact
        if (!target.toPath().startsWith(root.toPath())) return artifact
        if (!target.isFile || Files.isSymbolicLink(target.toPath())) return artifact
        return artifact.copy(sizeBytes = target.length())
    }

    private companion object {
        const val MAX_RECORDS = 10_000
        val lock = Any()
        val changeSignal = MutableStateFlow(0L)
    }
}

/** Admission for files explicitly registered in the durable artifact ledger. */
internal object WorkspaceArtifactCapacityPolicy {
    const val MAX_MANAGED_BYTES = 512L * 1024L * 1024L
    const val MIN_FREE_BYTES = 64L * 1024L * 1024L

    fun requireAdmission(
        existing: List<WorkspaceArtifact>,
        incoming: WorkspaceArtifact,
        usableBytes: Long,
    ) {
        if (incoming.status != WorkspaceArtifact.STATUS_VERIFIED || incoming.location != "workspace") return
        var managedBytes = incoming.sizeBytes ?: 0L
        require(managedBytes <= MAX_MANAGED_BYTES) { "ARTIFACT_STORAGE_QUOTA_EXCEEDED" }
        existing.asSequence()
            .filter { it.id != incoming.id }
            .filter { it.status == WorkspaceArtifact.STATUS_VERIFIED && it.location == "workspace" }
            .forEach { item ->
                val itemBytes = item.sizeBytes ?: 0L
                require(itemBytes <= MAX_MANAGED_BYTES - managedBytes) {
                    "ARTIFACT_STORAGE_QUOTA_EXCEEDED"
                }
                managedBytes += itemBytes
            }
        require(managedBytes <= MAX_MANAGED_BYTES) {
            "ARTIFACT_STORAGE_QUOTA_EXCEEDED"
        }
        require(usableBytes >= MIN_FREE_BYTES) { "ARTIFACT_STORAGE_LOW_SPACE" }
    }
}
