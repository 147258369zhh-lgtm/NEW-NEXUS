package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.zhh_lgtm.nexus.application.api.ArtifactLedger
import io.github.zhh_lgtm.nexus.application.api.ArtifactRecord
import io.github.zhh_lgtm.nexus.application.api.ArtifactStatus

internal class NexusArtifactLedger(private val context: Context) : ArtifactLedger {
    private val store = WorkspaceArtifactStore(context)

    override fun list(limit: Int): List<ArtifactRecord> = store.list(limit).map { it.toContract() }
    fun find(id: String): ArtifactRecord? = store.find(id)?.toContract()
    fun forSubject(type: String, id: String): List<ArtifactRecord> = store.forSubject(type, id).map { it.toContract() }
    fun forProject(id: String): List<ArtifactRecord> = store.forProject(id).map { it.toContract() }

    override fun record(record: ArtifactRecord): ArtifactRecord {
        val mediaType = resolvedMediaType(record)
        val stored = store.record(
            runId = record.runId,
            path = record.path,
            label = record.label,
            kind = record.kind,
            status = record.status.wireValue,
            now = record.createdAt,
            projectId = record.projectId,
            id = record.id, subjectType = record.subjectType, subjectId = record.subjectId, location = record.location,
            mediaType = mediaType, sizeBytes = record.sizeBytes,
            verifiedAt = record.verifiedAt ?: record.createdAt.takeIf { record.status == ArtifactStatus.VERIFIED },
        )
        if (record.status == ArtifactStatus.VERIFIED && stored.status != WorkspaceArtifact.STATUS_VERIFIED) {
            store.updateStatus(stored.id, WorkspaceArtifact.STATUS_VERIFIED, record.verifiedAt ?: record.createdAt)
        }
        if (record.status == ArtifactStatus.VERIFIED && GeneratedFilesPublicationStore.read(context, record.id) == null) {
            runCatching { GeneratedFilesArchive.publishArtifact(context, record.copy(mediaType = mediaType)) }.getOrNull()?.let { published ->
                GeneratedFilesPublicationStore.write(context, record.id, published)
            }
        }
        return checkNotNull(store.find(stored.id)).toContract()
    }

    private fun resolvedMediaType(record: ArtifactRecord): String = runCatching {
        if (record.status != ArtifactStatus.VERIFIED) return@runCatching NexusArtifactMedia.mimeType(record.mediaType, record.label, record.path)
        val source = when (record.location) {
            "content" -> context.contentResolver.openInputStream(android.net.Uri.parse(record.path))
            "workspace" -> {
                val root = io.github.mangi.eta.agent.terminal.TerminalPrivateStorage.workspace(context.filesDir).canonicalFile
                val file = java.io.File(root, record.path).canonicalFile
                check(file.toPath().startsWith(root.toPath()))
                file.inputStream()
            }
            "device" -> java.io.File(record.path).inputStream()
            else -> null
        }
        val header = source?.use { input -> ByteArray(512).let { buffer ->
            val count = input.read(buffer)
            if (count <= 0) byteArrayOf() else buffer.copyOf(count)
        } } ?: byteArrayOf()
        val supplied = record.mediaType ?: if (record.location == "content")
            context.contentResolver.getType(android.net.Uri.parse(record.path)) else null
        NexusArtifactMedia.detectedMimeType(supplied, record.label, record.path, header)
    }.getOrElse { NexusArtifactMedia.mimeType(record.mediaType, record.label, record.path) }

    override fun updateStatus(id: String, status: ArtifactStatus, now: Long): Boolean =
        store.updateStatus(id, status.wireValue, now)

    override fun remove(id: String): Boolean = store.remove(id)

    private fun WorkspaceArtifact.toContract() = ArtifactRecord(
        id = id,
        runId = runId,
        path = path,
        label = label,
        kind = kind,
        status = when (status) {
            WorkspaceArtifact.STATUS_PENDING -> ArtifactStatus.PENDING
            WorkspaceArtifact.STATUS_VERIFIED -> ArtifactStatus.VERIFIED
            else -> ArtifactStatus.FAILED
        },
        createdAt = createdAt,
        verifiedAt = verifiedAt,
        projectId = projectId,
        subjectType = subjectType, subjectId = subjectId, location = location,
        mediaType = NexusArtifactMedia.mimeType(mediaType, label, path), sizeBytes = sizeBytes,
    )

    private val ArtifactStatus.wireValue: String
        get() = when (this) {
            ArtifactStatus.PENDING -> WorkspaceArtifact.STATUS_PENDING
            ArtifactStatus.VERIFIED -> WorkspaceArtifact.STATUS_VERIFIED
            ArtifactStatus.FAILED -> WorkspaceArtifact.STATUS_FAILED
        }
}
