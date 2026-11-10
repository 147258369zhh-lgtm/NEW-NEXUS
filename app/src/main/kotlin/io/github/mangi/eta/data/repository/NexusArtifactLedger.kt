package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.zhh_lgtm.nexus.application.api.ArtifactLedger
import io.github.zhh_lgtm.nexus.application.api.ArtifactRecord
import io.github.zhh_lgtm.nexus.application.api.ArtifactStatus

internal class NexusArtifactLedger(context: Context) : ArtifactLedger {
    private val store = WorkspaceArtifactStore(context)

    override fun list(limit: Int): List<ArtifactRecord> = store.list(limit).map { it.toContract() }

    override fun record(record: ArtifactRecord): ArtifactRecord {
        val stored = store.record(
            runId = record.runId,
            path = record.path,
            label = record.label,
            kind = record.kind,
            status = record.status.wireValue,
            now = record.createdAt,
            projectId = record.projectId,
        )
        if (record.status == ArtifactStatus.VERIFIED && stored.status != WorkspaceArtifact.STATUS_VERIFIED) {
            store.updateStatus(stored.id, WorkspaceArtifact.STATUS_VERIFIED, record.verifiedAt ?: record.createdAt)
        }
        return store.list(200).firstOrNull { it.id == stored.id }?.toContract() ?: stored.toContract()
    }

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
    )

    private val ArtifactStatus.wireValue: String
        get() = when (this) {
            ArtifactStatus.PENDING -> WorkspaceArtifact.STATUS_PENDING
            ArtifactStatus.VERIFIED -> WorkspaceArtifact.STATUS_VERIFIED
            ArtifactStatus.FAILED -> WorkspaceArtifact.STATUS_FAILED
        }
}
