package io.github.mangi.eta.fork.learning

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
internal interface LearningDao {
    @Query("SELECT * FROM learning_candidates WHERE id = :id")
    suspend fun candidate(id: String): LearningCandidateEntity?

    @Query("SELECT * FROM learning_candidates WHERE content_hash = :contentHash LIMIT 1")
    suspend fun candidateByHash(contentHash: String): LearningCandidateEntity?

    @Query(
        "SELECT * FROM learning_candidates ORDER BY " +
            "CASE status WHEN 'butler_approved' THEN 0 WHEN 'conflict' THEN 1 WHEN 'pending' THEN 2 " +
            "WHEN 'butler_review' THEN 3 WHEN 'confirmed' THEN 4 ELSE 5 END, " +
            "updated_at DESC LIMIT :limit",
    )
    suspend fun candidates(limit: Int = 300): List<LearningCandidateEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCandidate(candidate: LearningCandidateEntity)

    @Update
    suspend fun updateCandidate(candidate: LearningCandidateEntity)

    @Query(
        "UPDATE learning_candidates SET status = :status, updated_at = :updatedAt, " +
            "promoted_rule_id = :promotedRuleId, confirmed_at = :confirmedAt WHERE id = :id",
    )
    suspend fun updateCandidateState(
        id: String,
        status: String,
        updatedAt: Long,
        promotedRuleId: String? = null,
        confirmedAt: Long? = null,
    ): Int

    @Query("UPDATE learning_candidates SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touchCandidate(id: String, updatedAt: Long): Int

    @Query(
        "UPDATE learning_candidates SET status = :newStatus, updated_at = :updatedAt, " +
            "promoted_rule_id = :promotedRuleId, confirmed_at = :confirmedAt " +
            "WHERE id = :id AND status = :expectedStatus",
    )
    suspend fun transitionCandidateState(
        id: String,
        expectedStatus: String,
        newStatus: String,
        updatedAt: Long,
        promotedRuleId: String? = null,
        confirmedAt: Long? = null,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvidence(evidence: LearningEvidenceEntity): Long

    @Query("SELECT * FROM learning_evidence WHERE candidate_id = :candidateId ORDER BY observed_at ASC")
    suspend fun evidence(candidateId: String): List<LearningEvidenceEntity>

    @Query("SELECT * FROM learning_rules WHERE kind = :kind AND title = :title LIMIT 1")
    suspend fun ruleByKey(kind: String, title: String): LearningRuleEntity?

    @Query("SELECT * FROM learning_rules WHERE id = :id")
    suspend fun rule(id: String): LearningRuleEntity?

    @Query("SELECT * FROM learning_rules WHERE status = 'active' ORDER BY updated_at DESC")
    suspend fun activeRules(): List<LearningRuleEntity>

    @Query("SELECT * FROM learning_rules ORDER BY updated_at DESC")
    suspend fun allRules(): List<LearningRuleEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRule(rule: LearningRuleEntity)

    @Update
    suspend fun updateRule(rule: LearningRuleEntity)

    @Query(
        "UPDATE learning_rules SET content = :content, current_version = :newVersion, " +
            "status = :status, updated_at = :updatedAt " +
            "WHERE id = :id AND current_version = :expectedVersion",
    )
    suspend fun transitionRuleVersion(
        id: String,
        expectedVersion: Int,
        newVersion: Int,
        content: String,
        status: String,
        updatedAt: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRuleVersion(version: LearningRuleVersionEntity)

    @Query("SELECT * FROM learning_rule_versions WHERE rule_id = :ruleId ORDER BY version DESC")
    suspend fun ruleVersions(ruleId: String): List<LearningRuleVersionEntity>

    @Query("SELECT * FROM learning_rule_versions WHERE id = :id")
    suspend fun ruleVersion(id: String): LearningRuleVersionEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFeedback(feedback: LearningFeedbackEntity): Long

    @Query("SELECT * FROM learning_feedback WHERE candidate_id = :candidateId ORDER BY created_at ASC")
    suspend fun feedback(candidateId: String): List<LearningFeedbackEntity>
}
