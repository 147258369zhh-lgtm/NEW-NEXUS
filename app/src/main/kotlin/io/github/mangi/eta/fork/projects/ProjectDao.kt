package io.github.mangi.eta.fork.projects

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ProjectDao {
    @Query("SELECT EXISTS(SELECT 1 FROM branch_setup_receipts WHERE setup_id = :id)")
    suspend fun hasBranchSetupReceipt(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBranchSetupReceipt(receipt: io.github.mangi.eta.data.db.BranchSetupReceiptEntity)

    @Query("DELETE FROM branch_setup_receipts WHERE conversation_id = :id")
    suspend fun deleteBranchSetupReceipts(id: String)

    @Query("SELECT * FROM projects ORDER BY (archived_at IS NULL) DESC, updated_at DESC, id")
    fun projectsFlow(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :projectId")
    fun projectFlow(projectId: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :projectId")
    suspend fun project(projectId: String): ProjectEntity?

    @Query("SELECT * FROM projects WHERE root_identity_key = :rootIdentityKey LIMIT 1")
    suspend fun projectForRoot(rootIdentityKey: String): ProjectEntity?

    @Query(
        "SELECT project_subject_bindings.* FROM project_subject_bindings " +
            "INNER JOIN projects ON projects.id = project_subject_bindings.project_id " +
            "WHERE subject_type = :subjectType AND subject_id = :subjectId " +
            "AND projects.archived_at IS NULL LIMIT 1",
    )
    suspend fun activeBinding(subjectType: String, subjectId: String): ProjectSubjectBindingEntity?

    @Query("SELECT * FROM project_subject_bindings WHERE project_id = :projectId ORDER BY subject_type, subject_id")
    suspend fun bindingsForProject(projectId: String): List<ProjectSubjectBindingEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProject(project: ProjectEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBinding(binding: ProjectSubjectBindingEntity)

    @Query("DELETE FROM project_subject_bindings WHERE subject_type = :subjectType AND subject_id = :subjectId")
    suspend fun deleteBinding(subjectType: String, subjectId: String): Int

    @Query("UPDATE projects SET name = :name, updated_at = :now WHERE id = :projectId")
    suspend fun rename(projectId: String, name: String, now: Long): Int

    @Query("UPDATE projects SET archived_at = :archivedAt, updated_at = :now WHERE id = :projectId")
    suspend fun setArchived(projectId: String, archivedAt: Long?, now: Long): Int

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: String): Int
}
