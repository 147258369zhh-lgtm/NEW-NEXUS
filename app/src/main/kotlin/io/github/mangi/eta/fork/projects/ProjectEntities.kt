package io.github.mangi.eta.fork.projects

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "projects",
    indices = [
        Index(value = ["root_identity_key"], unique = true),
        Index(value = ["archived_at"]),
    ],
)
internal data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "root_identity_key") val rootIdentityKey: String,
    @ColumnInfo(name = "root_display_name") val rootDisplayName: String,
    val source: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
)

@Entity(
    tableName = "project_subject_bindings",
    primaryKeys = ["subject_type", "subject_id"],
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("project_id")],
)
internal data class ProjectSubjectBindingEntity(
    @ColumnInfo(name = "project_id") val projectId: String,
    @ColumnInfo(name = "subject_type") val subjectType: String,
    @ColumnInfo(name = "subject_id") val subjectId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

internal fun ProjectSubjectBindingEntity.toModel() = ProjectSubjectBinding(
    projectId = projectId,
    subjectType = subjectType,
    subjectId = subjectId,
)
