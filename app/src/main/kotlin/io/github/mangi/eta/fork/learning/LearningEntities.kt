package io.github.mangi.eta.fork.learning

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "learning_candidates",
    indices = [
        Index(value = ["content_hash"], unique = true),
        Index("status"),
        Index("updated_at"),
    ],
)
internal data class LearningCandidateEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val content: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    val status: String,
    val confidence: Double,
    @ColumnInfo(name = "source_ref") val sourceRef: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "promoted_rule_id") val promotedRuleId: String? = null,
    @ColumnInfo(name = "confirmed_at") val confirmedAt: Long? = null,
)

@Entity(
    tableName = "learning_evidence",
    foreignKeys = [
        ForeignKey(
            entity = LearningCandidateEntity::class,
            parentColumns = ["id"],
            childColumns = ["candidate_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("candidate_id"), Index("observed_at")],
)
internal data class LearningEvidenceEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "candidate_id") val candidateId: String,
    @ColumnInfo(name = "source_ref") val sourceRef: String,
    @ColumnInfo(name = "excerpt_hash") val excerptHash: String,
    val excerpt: String,
    @ColumnInfo(name = "observed_at") val observedAt: Long,
)

@Entity(
    tableName = "learning_rules",
    indices = [Index(value = ["kind", "title"], unique = true), Index("status")],
)
internal data class LearningRuleEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val content: String,
    @ColumnInfo(name = "current_version") val currentVersion: Int,
    val status: String,
    val pinned: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "learning_rule_versions",
    foreignKeys = [
        ForeignKey(
            entity = LearningRuleEntity::class,
            parentColumns = ["id"],
            childColumns = ["rule_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("rule_id"), Index(value = ["rule_id", "version"], unique = true)],
)
internal data class LearningRuleVersionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "rule_id") val ruleId: String,
    val version: Int,
    val content: String,
    @ColumnInfo(name = "candidate_id") val candidateId: String?,
    val reason: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "learning_feedback",
    foreignKeys = [
        ForeignKey(
            entity = LearningCandidateEntity::class,
            parentColumns = ["id"],
            childColumns = ["candidate_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("candidate_id"), Index("created_at")],
)
internal data class LearningFeedbackEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "candidate_id") val candidateId: String,
    val action: String,
    val note: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
