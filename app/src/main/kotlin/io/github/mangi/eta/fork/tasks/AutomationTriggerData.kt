package io.github.mangi.eta.fork.tasks

import androidx.room.*

@Entity(tableName = "automation_triggers", foreignKeys = [ForeignKey(entity = AgentTaskDefinitionEntity::class,
    parentColumns = ["id"], childColumns = ["definitionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("definitionId")])
internal data class AutomationTriggerEntity(@PrimaryKey val id: String, val definitionId: String,
    val type: String, val source: String = "", val extension: String = "", val cooldownSeconds: Int = 900,
    val enabled: Boolean = true, val cursor: String = "", val lastFiredAt: Long = 0, val detail: String = "")

@Dao
internal interface AutomationTriggerDao {
    @Query("SELECT * FROM automation_triggers") suspend fun all(): List<AutomationTriggerEntity>
    @Query("SELECT * FROM automation_triggers ORDER BY id") fun observe(): kotlinx.coroutines.flow.Flow<List<AutomationTriggerEntity>>
    @Query("SELECT * FROM automation_triggers WHERE id = :id") suspend fun get(id: String): AutomationTriggerEntity?
    @Upsert suspend fun save(value: AutomationTriggerEntity)
    @Query("DELETE FROM automation_triggers WHERE definitionId = :id") suspend fun deleteFor(id: String)
}
