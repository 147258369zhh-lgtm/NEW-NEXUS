package io.github.mangi.eta.agent.memory

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "memory_entries", indices = [Index("scope"), Index("status"), Index("fingerprint")])
data class MemoryEntry(
    @PrimaryKey val id: String,
    val scope: String,
    val layer: String,
    val topic: String,
    val content: String,
    val evidence: String,
    val source: String,
    val fingerprint: String,
    val status: String = "pending",
    val confidence: Double = 0.7,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long? = null,
    val conflictIds: String = "",
    val supersedes: String = "",
)

@Entity(tableName = "memory_jobs", indices = [Index("status"), Index("createdAt")])
internal data class MemoryJob(
    @PrimaryKey val id: String,
    val source: String,
    val scopes: String,
    val text: String,
    val createdAt: Long,
    val status: String = "queued",
    val attempts: Int = 0,
    val updatedAt: Long = createdAt,
    val detail: String = "",
)
