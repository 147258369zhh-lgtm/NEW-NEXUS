package io.github.mangi.eta.agent.skill

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "skill_experiences")
internal data class SkillExperience(
    @PrimaryKey val runId: String,
    val scope: String,
    val digest: String,
    val tools: String,
    val createdAt: Long,
    val state: String = "queued",
    val recipeId: String = "",
    val detail: String = "",
)

@Entity(tableName = "skill_recipes")
internal data class SkillRecipe(
    @PrimaryKey val id: String,
    val scope: String,
    val title: String,
    val description: String,
    val body: String,
    val tools: String,
    val state: String = "collecting",
    val version: Int = 0,
    val revision: String = "",
    val updatedAt: Long,
)

@Entity(tableName = "skill_recipe_versions", primaryKeys = ["recipeId", "version"])
internal data class SkillRecipeVersion(val recipeId: String, val version: Int, val title: String,
    val description: String, val body: String, val tools: String, val createdAt: Long)

@Dao
internal interface SkillEvolutionDao {
    @Query("SELECT * FROM skill_recipes ORDER BY updatedAt DESC")
    fun observe(): Flow<List<SkillRecipe>>
    @Query("SELECT * FROM skill_recipes WHERE id = :id")
    suspend fun recipe(id: String): SkillRecipe?
    @Query("SELECT * FROM skill_recipes ORDER BY updatedAt DESC") suspend fun recipes(): List<SkillRecipe>
    @Query("UPDATE skill_experiences SET recipeId = :target WHERE recipeId = :source")
    suspend fun moveEvidence(source: String, target: String)
    @Query("SELECT * FROM skill_experiences WHERE state = 'queued' ORDER BY createdAt LIMIT 1")
    suspend fun next(): SkillExperience?
    @Query("SELECT * FROM skill_experiences WHERE recipeId = :id ORDER BY createdAt DESC")
    suspend fun evidence(id: String): List<SkillExperience>
    @Query("SELECT COUNT(*) FROM skill_experiences WHERE state = 'queued'")
    suspend fun queued(): Int
    @Query("SELECT COUNT(*) FROM skill_experiences WHERE state IN ('accepted','ignored','failed') AND createdAt >= :after")
    suspend fun reviewedSince(after: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(value: SkillExperience): Long
    @Update suspend fun update(value: SkillExperience)
    @Upsert suspend fun save(value: SkillRecipe)
    @Insert suspend fun version(value: SkillRecipeVersion)
    @Query("SELECT * FROM skill_recipe_versions WHERE recipeId = :id ORDER BY version DESC")
    suspend fun versions(id: String): List<SkillRecipeVersion>
    @Query("DELETE FROM skill_experiences WHERE createdAt < :before AND recipeId = ''")
    suspend fun prune(before: Long)
}
