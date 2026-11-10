package io.github.mangi.eta.fork.learning

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.UUID

internal data class LearningProposalInput(
    val kind: LearningKind,
    val title: String,
    val content: String,
    val evidence: String = "",
    val sourceRef: String = "manual",
)

internal data class LearningEvidenceView(
    val id: String,
    val sourceRef: String,
    val excerpt: String,
    val observedAt: Long,
)

internal data class LearningCandidateView(
    val id: String,
    val kind: LearningKind,
    val title: String,
    val content: String,
    val status: LearningStatus,
    val confidence: Double,
    val sourceRef: String,
    val createdAt: Long,
    val updatedAt: Long,
    val promotedRuleId: String?,
    val evidence: List<LearningEvidenceView>,
    val conflicts: List<LearningConflictView> = emptyList(),
    val reviewSummary: String = "",
)

internal data class LearningConflictView(val id: String, val title: String, val content: String, val version: Int)

internal data class LearningRuleVersionView(
    val id: String,
    val version: Int,
    val content: String,
    val reason: String,
    val createdAt: Long,
)

internal data class LearningRuleView(
    val id: String,
    val kind: LearningKind,
    val title: String,
    val content: String,
    val currentVersion: Int,
    val active: Boolean,
    val pinned: Boolean,
    val versions: List<LearningRuleVersionView>,
)

internal data class LearningSnapshot(
    val candidates: List<LearningCandidateView>,
    val rules: List<LearningRuleView>,
)

internal sealed interface LearningProposalResult {
    data class Created(val candidate: LearningCandidateView) : LearningProposalResult
    data class Existing(val candidate: LearningCandidateView) : LearningProposalResult
    data object BlockedByPolicy : LearningProposalResult
    data object Empty : LearningProposalResult
}

internal sealed interface LearningMutationResult {
    data class Success(val snapshot: LearningSnapshot) : LearningMutationResult
    data class Rejected(val reason: String) : LearningMutationResult
}

internal class LearningRepository(
    private val database: LearningDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao = database.learningDao()

    suspend fun propose(input: LearningProposalInput): LearningProposalResult = database.withTransaction {
        val now = clock()
        val activeRules = dao.activeRules()
        val policy = when (
            val result = LearningPolicy.evaluate(
                kind = input.kind,
                title = input.title,
                content = input.content,
                evidence = input.evidence,
                activeRules = activeRules,
            )
        ) {
            LearningPolicyResult.Blocked -> return@withTransaction LearningProposalResult.BlockedByPolicy
            LearningPolicyResult.Empty -> return@withTransaction LearningProposalResult.Empty
            is LearningPolicyResult.Accepted -> result
        }
        val hash = LearningPolicy.fingerprint(input.kind, policy.title, policy.content)
        val sourceRef = LearningPolicy.sanitizeForStorage(input.sourceRef, 240)
        val evidenceHash = LearningPolicy.evidenceHash("$sourceRef\n${policy.evidence}")
        val evidence = LearningEvidenceEntity(
            id = "",
            candidateId = "",
            sourceRef = sourceRef,
            excerptHash = evidenceHash,
            excerpt = policy.evidence,
            observedAt = now,
        )
        (dao.candidateByHash(hash) ?: dao.candidates(1000).firstOrNull {
            it.kind == input.kind.wireValue && io.github.mangi.eta.agent.memory.MemoryAutomationPolicy.equivalent(it.content, policy.content)
        })?.let { existing ->
            dao.insertEvidence(evidence.copy(id = "${existing.id}:$evidenceHash", candidateId = existing.id))
            dao.touchCandidate(existing.id, now)
            val reviewed = reviewCandidateInTransaction(checkNotNull(dao.candidate(existing.id)), now)
            return@withTransaction LearningProposalResult.Existing(candidateView(reviewed))
        }
        val candidateId = ids()
        val candidate = LearningCandidateEntity(
            id = candidateId,
            kind = input.kind.wireValue,
            title = policy.title,
            content = policy.content,
            contentHash = hash,
            status = LearningStatus.BUTLER_REVIEW.wireValue,
            confidence = policy.confidence,
            sourceRef = sourceRef,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertCandidate(candidate)
        dao.insertEvidence(
            evidence.copy(
                id = "$candidateId:$evidenceHash",
                candidateId = candidateId,
            ),
        )
        val reviewed = reviewCandidateInTransaction(candidate, now)
        LearningProposalResult.Created(candidateView(reviewed))
    }

    suspend fun confirm(candidateId: String, overrideConflict: Boolean = false, expectedConflict: String? = null): LearningMutationResult {
        val now = clock()
        val outcome = database.withTransaction {
            var candidate = dao.candidate(candidateId)
                ?: return@withTransaction Rejected("学习候选不存在")
            var status = LearningStatus.entries.firstOrNull { it.wireValue == candidate.status }
                ?: return@withTransaction Rejected("学习候选状态无效")
            if (status in setOf(
                    LearningStatus.CONFIRMED,
                    LearningStatus.REJECTED,
                    LearningStatus.PROMOTED,
                )
            ) {
                return@withTransaction Rejected("该候选已结束，不能再次确认")
            }
            if (status != LearningStatus.BUTLER_APPROVED) {
                candidate = reviewCandidateInTransaction(candidate, now)
                status = LearningStatus.entries.firstOrNull { it.wireValue == candidate.status }
                    ?: return@withTransaction Rejected("软件管家审核状态无效")
            }
            if (status != LearningStatus.BUTLER_APPROVED) {
                return@withTransaction Rejected("候选未通过软件管家审核，不能提交人工批准")
            }
            val currentConflicts = conflictsFor(candidate)
            val signature = conflictSignature(currentConflicts)
            if (expectedConflict != null && expectedConflict != signature)
                return@withTransaction Rejected("冲突规则已更新，请重新对照")
            if (currentConflicts.isNotEmpty() && !overrideConflict) {
                return@withTransaction Rejected("该候选与现有规则冲突，必须由用户显式覆盖后才能确认")
            }
            val transitioned = dao.transitionCandidateState(
                id = candidateId,
                expectedStatus = candidate.status,
                newStatus = LearningStatus.CONFIRMED.wireValue,
                updatedAt = now,
                confirmedAt = now,
            )
            if (transitioned == 0) return@withTransaction Rejected("候选状态已被其他操作更新，请刷新后重试")
            dao.insertFeedback(
                LearningFeedbackEntity(
                    id = ids(),
                    candidateId = candidateId,
                    action = "confirm",
                    note = if (overrideConflict && currentConflicts.isNotEmpty()) "override:$signature" else "user-confirmed",
                    createdAt = now,
                ),
            )
            Accepted
        }
        if (outcome is Rejected) return rejected(outcome.reason)
        return success()
    }

    suspend fun reviewCandidate(candidateId: String): LearningMutationResult = database.withTransaction {
        val candidate = dao.candidate(candidateId)
            ?: return@withTransaction LearningMutationResult.Rejected("学习候选不存在")
        val reviewed = reviewCandidateInTransaction(candidate, clock().coerceAtLeast(candidate.updatedAt + 1))
        if (reviewed.status == LearningStatus.REJECTED.wireValue) {
            val reason = dao.feedback(candidateId).lastOrNull { it.action == "butler_reject" }?.note
                ?: "软件管家审核未通过"
            return@withTransaction LearningMutationResult.Rejected(reason)
        }
        success()
    }

    suspend fun reject(candidateId: String): LearningMutationResult {
        val now = clock()
        val outcome = database.withTransaction {
            val candidate = dao.candidate(candidateId)
                ?: return@withTransaction Rejected("学习候选不存在")
            if (candidate.status in setOf(
                    LearningStatus.REJECTED.wireValue,
                    LearningStatus.PROMOTED.wireValue,
                )
            ) {
                return@withTransaction Rejected("该候选已结束，不能再次拒绝")
            }
            val transitioned = dao.transitionCandidateState(
                id = candidateId,
                expectedStatus = candidate.status,
                newStatus = LearningStatus.REJECTED.wireValue,
                updatedAt = now,
            )
            if (transitioned == 0) return@withTransaction Rejected("候选状态已被其他操作更新，请刷新后重试")
            dao.insertFeedback(
                LearningFeedbackEntity(
                    id = ids(),
                    candidateId = candidateId,
                    action = "reject",
                    note = "user-rejected",
                    createdAt = now,
                ),
            )
            Accepted
        }
        if (outcome is Rejected) return rejected(outcome.reason)
        return success()
    }

    suspend fun promote(candidateId: String): LearningMutationResult {
        val now = clock()
        val outcome = database.withTransaction {
            val candidate = dao.candidate(candidateId)
                ?: return@withTransaction Rejected("学习候选不存在")
            if (candidate.status != LearningStatus.CONFIRMED.wireValue) {
                return@withTransaction Rejected("只有用户已确认的候选才能晋升")
            }
            val kind = LearningKind.fromWire(candidate.kind)
                ?: return@withTransaction Rejected("学习类型无效")
            val currentConflicts = conflictsFor(candidate)
            if (currentConflicts.isNotEmpty()) {
                val approved = dao.feedback(candidateId).lastOrNull { it.action == "confirm" }?.note
                if (approved != "override:${conflictSignature(currentConflicts)}") {
                    dao.updateCandidateState(candidateId, "conflict", now)
                    return@withTransaction Rejected("现有规则已变化，请重新审阅冲突后确认")
                }
            }
            val existing = dao.ruleByKey(kind.wireValue, candidate.title) ?: currentConflicts.firstOrNull()
            val ruleId: String
            if (existing == null) {
                ruleId = ids()
                dao.insertRule(LearningRuleEntity(
                    id = ruleId,
                    kind = kind.wireValue,
                    title = candidate.title,
                    content = candidate.content,
                    currentVersion = 1,
                    status = LearningRuleStatus.ACTIVE.wireValue,
                    createdAt = now,
                    updatedAt = now,
                ))
                dao.insertRuleVersion(LearningRuleVersionEntity(
                    id = ids(), ruleId = ruleId, version = 1, content = candidate.content,
                    candidateId = candidateId, reason = "confirmed-promotion", createdAt = now,
                ))
            } else {
                ruleId = existing.id
                val version = existing.currentVersion + 1
                val updated = dao.transitionRuleVersion(
                    id = existing.id,
                    expectedVersion = existing.currentVersion,
                    newVersion = version,
                    content = candidate.content,
                    status = LearningRuleStatus.ACTIVE.wireValue,
                    updatedAt = now,
                )
                if (updated == 0) {
                    return@withTransaction Rejected("学习规则已被其他操作更新，请刷新后重试")
                }
                dao.insertRuleVersion(LearningRuleVersionEntity(
                    id = ids(), ruleId = ruleId, version = version, content = candidate.content,
                    candidateId = candidateId, reason = "confirmed-promotion", createdAt = now,
                ))
            }
            val transitioned = dao.transitionCandidateState(
                id = candidateId,
                expectedStatus = LearningStatus.CONFIRMED.wireValue,
                newStatus = LearningStatus.PROMOTED.wireValue,
                updatedAt = now,
                promotedRuleId = ruleId,
                confirmedAt = candidate.confirmedAt ?: now,
            )
            if (transitioned == 0) {
                return@withTransaction Rejected("候选已被其他操作晋升，请刷新后重试")
            }
            currentConflicts.filter { it.id != ruleId }.forEach {
                dao.updateRule(it.copy(status = LearningRuleStatus.ARCHIVED.wireValue, updatedAt = now))
            }
            dao.insertFeedback(LearningFeedbackEntity(
                id = ids(), candidateId = candidateId, action = "promote", note = ruleId, createdAt = now,
            ))
            Accepted
        }
        if (outcome is Rejected) return rejected(outcome.reason)
        return success()
    }

    suspend fun rollback(ruleId: String, versionId: String): LearningMutationResult {
        val rule = dao.rule(ruleId) ?: return rejected("学习规则不存在")
        val target = dao.ruleVersion(versionId)
            ?.takeIf { it.ruleId == ruleId }
            ?: return rejected("目标版本不属于该学习规则")
        val now = clock()
        val nextVersion = rule.currentVersion + 1
        val outcome = database.withTransaction {
            val updated = dao.transitionRuleVersion(
                id = rule.id,
                expectedVersion = rule.currentVersion,
                newVersion = nextVersion,
                content = target.content,
                status = LearningRuleStatus.ACTIVE.wireValue,
                updatedAt = now,
            )
            if (updated == 0) return@withTransaction Rejected("学习规则已被其他操作更新，请刷新后重试")
            dao.insertRuleVersion(LearningRuleVersionEntity(
                id = ids(), ruleId = rule.id, version = nextVersion, content = target.content,
                candidateId = target.candidateId, reason = "rollback-to-v${target.version}", createdAt = now,
            ))
            Accepted
        }
        if (outcome is Rejected) return rejected(outcome.reason)
        return success()
    }

    suspend fun archiveRule(ruleId: String): LearningMutationResult = database.withTransaction {
        val rule = dao.rule(ruleId) ?: return@withTransaction rejected("学习规则不存在")
        dao.updateRule(rule.copy(status = LearningRuleStatus.ARCHIVED.wireValue, updatedAt = clock()))
        success()
    }

    suspend fun pinRule(ruleId: String, pinned: Boolean): LearningMutationResult = database.withTransaction {
        val rule = dao.rule(ruleId) ?: return@withTransaction rejected("学习规则不存在")
        dao.updateRule(rule.copy(pinned = pinned, updatedAt = clock()))
        success()
    }

    suspend fun editCandidate(id: String, content: String): LearningMutationResult = database.withTransaction {
        val candidate = dao.candidate(id) ?: return@withTransaction rejected("候选不存在")
        if (candidate.status !in setOf("butler_approved", "pending", "conflict")) {
            return@withTransaction rejected("只有待人工批准的候选可以修改")
        }
        val kind = LearningKind.fromWire(candidate.kind) ?: return@withTransaction rejected("类型无效")
        val policy = LearningPolicy.evaluate(kind, candidate.title, content, candidateView(candidate).evidence.firstOrNull()?.excerpt.orEmpty(), dao.activeRules())
        if (policy !is LearningPolicyResult.Accepted) return@withTransaction rejected("内容无效或用户禁止保存")
        val hash = LearningPolicy.fingerprint(kind, policy.title, policy.content)
        if (dao.candidateByHash(hash)?.id?.let { it != id } == true) return@withTransaction rejected("已存在相同候选")
        val edited = candidate.copy(
            content = policy.content,
            contentHash = hash,
            status = LearningStatus.BUTLER_REVIEW.wireValue,
            updatedAt = clock().coerceAtLeast(candidate.updatedAt + 1),
        )
        dao.updateCandidate(edited)
        reviewCandidateInTransaction(edited, edited.updatedAt)
        success()
    }

    private suspend fun reviewCandidateInTransaction(
        candidate: LearningCandidateEntity,
        now: Long,
    ): LearningCandidateEntity {
        if (candidate.status == LearningStatus.BUTLER_APPROVED.wireValue ||
            candidate.status in setOf(
                LearningStatus.CONFIRMED.wireValue,
                LearningStatus.PROMOTED.wireValue,
                LearningStatus.REJECTED.wireValue,
            )
        ) return candidate
        val kind = LearningKind.fromWire(candidate.kind) ?: return candidate.copy(status = LearningStatus.REJECTED.wireValue)
        val evidence = dao.evidence(candidate.id)
        val conflicts = conflictsFor(candidate)
        val decision = SedimentReviewPolicy.reviewLearning(
            kind = kind,
            title = candidate.title,
            content = candidate.content,
            evidence = evidence.joinToString("\n") { it.excerpt },
            sourceRef = candidate.sourceRef,
            confidence = candidate.confidence,
            hasConflict = conflicts.isNotEmpty(),
        )
        val nextStatus = if (decision.approved) LearningStatus.BUTLER_APPROVED else LearningStatus.REJECTED
        val transitioned = dao.transitionCandidateState(
            id = candidate.id,
            expectedStatus = candidate.status,
            newStatus = nextStatus.wireValue,
            updatedAt = now.coerceAtLeast(candidate.updatedAt + 1),
        )
        val updated = if (transitioned == 1) {
            dao.insertFeedback(
                LearningFeedbackEntity(
                    id = "butler-${candidate.id}-${nextStatus.wireValue}",
                    candidateId = candidate.id,
                    action = if (decision.approved) "butler_approve" else "butler_reject",
                    note = decision.summary.take(240),
                    createdAt = now,
                ),
            )
            dao.candidate(candidate.id) ?: candidate.copy(status = nextStatus.wireValue)
        } else {
            dao.candidate(candidate.id) ?: candidate
        }
        return updated
    }

    private suspend fun conflictsFor(candidate: LearningCandidateEntity): List<LearningRuleEntity> {
        val kind = LearningKind.fromWire(candidate.kind) ?: LearningKind.FACT
        return dao.activeRules().filter { rule ->
            (LearningPolicy.evaluate(kind, candidate.title, candidate.content, "", listOf(rule)) as? LearningPolicyResult.Accepted)?.conflict != null
        }
    }

    private fun conflictSignature(rules: List<LearningRuleEntity>): String = rules.sortedBy { it.id }
        .joinToString(",") { "${it.id}:${it.currentVersion}" }

    suspend fun context(maxChars: Int = 2400): String = dao.activeRules()
        .sortedByDescending { it.pinned }
        .joinToString("\n") { "${it.title}: ${it.content}" }.take(maxChars.coerceAtLeast(0))

    fun observe() = database.invalidationTracker.createFlow(
        "learning_candidates", "learning_rules", "learning_evidence", "learning_rule_versions",
    ).map { snapshot() }.flowOn(Dispatchers.IO)

    suspend fun snapshot(): LearningSnapshot = database.withTransaction {
        val candidates = dao.candidates().map { rawCandidate ->
            val candidate = if (rawCandidate.status in setOf(
                    LearningStatus.BUTLER_REVIEW.wireValue,
                    LearningStatus.PENDING.wireValue,
                    LearningStatus.CONFLICT.wireValue,
                )
            ) reviewCandidateInTransaction(rawCandidate, clock()) else rawCandidate
            candidateView(candidate, dao.evidence(candidate.id).map { evidence ->
                LearningEvidenceView(evidence.id, evidence.sourceRef, evidence.excerpt, evidence.observedAt)
            })
        }
        val rules = dao.allRules().map { rule ->
            LearningRuleView(
                id = rule.id,
                kind = LearningKind.fromWire(rule.kind) ?: LearningKind.FACT,
                title = rule.title,
                content = rule.content,
                currentVersion = rule.currentVersion,
                active = rule.status == LearningRuleStatus.ACTIVE.wireValue,
                pinned = rule.pinned,
                versions = dao.ruleVersions(rule.id).map { version ->
                    LearningRuleVersionView(version.id, version.version, version.content, version.reason, version.createdAt)
                },
            )
        }
        LearningSnapshot(candidates, rules)
    }

    suspend fun search(query: String, limit: Int = 12): List<LearningRuleView> {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return emptyList()
        return dao.activeRules().asSequence()
            .filter { rule ->
                (rule.title + '\n' + rule.content).lowercase().contains(normalized) ||
                    normalized.split(Regex("\\s+")).all { token ->
                        token.length < 2 || (rule.title + '\n' + rule.content).lowercase().contains(token)
                    }
            }
            .take(limit.coerceIn(1, 50))
            .map { rule ->
                LearningRuleView(
                    id = rule.id,
                    kind = LearningKind.fromWire(rule.kind) ?: LearningKind.FACT,
                    title = rule.title,
                    content = rule.content,
                    currentVersion = rule.currentVersion,
                    active = true,
                    pinned = rule.pinned,
                    versions = emptyList(),
                )
            }
            .toList()
    }

    suspend fun recordFailure(
        errorCode: String,
        message: String,
        sourceRef: String,
    ): LearningProposalResult = propose(LearningProposalInput(
        kind = LearningKind.FAILURE_FIX,
        title = LearningPolicy.sanitizeForStorage("运行时失败：$errorCode", 120),
        content = LearningPolicy.sanitizeForStorage(
            "遇到 $errorCode 时先保留原始状态并检查恢复条件。观察：${message.take(500)}",
            1_200,
        ),
        evidence = LearningPolicy.sanitizeForStorage(message, 1_000),
        sourceRef = sourceRef,
    ))

    private suspend fun candidateView(
        candidate: LearningCandidateEntity,
        evidence: List<LearningEvidenceView>? = null,
    ): LearningCandidateView {
        val actualEvidence = evidence ?: dao.evidence(candidate.id).map { row ->
            LearningEvidenceView(row.id, row.sourceRef, row.excerpt, row.observedAt)
        }
        return LearningCandidateView(
        id = candidate.id,
        kind = LearningKind.fromWire(candidate.kind) ?: LearningKind.FACT,
        title = candidate.title,
        content = candidate.content,
        status = LearningStatus.entries.firstOrNull { it.wireValue == candidate.status } ?: LearningStatus.BUTLER_REVIEW,
        confidence = candidate.confidence,
        sourceRef = candidate.sourceRef,
        createdAt = candidate.createdAt,
        updatedAt = candidate.updatedAt,
        promotedRuleId = candidate.promotedRuleId,
            evidence = actualEvidence,
            conflicts = conflictsFor(candidate).map { LearningConflictView(it.id, it.title, it.content, it.currentVersion) },
            reviewSummary = dao.feedback(candidate.id)
                .lastOrNull { it.action == "butler_approve" || it.action == "butler_reject" }
                ?.note
                .orEmpty(),
        )
    }

    private suspend fun success() = LearningMutationResult.Success(snapshot())

    private fun rejected(reason: String) = LearningMutationResult.Rejected(reason)

    private data object Accepted
    private data class Rejected(val reason: String)

    companion object {
        @Volatile
        private var instance: LearningRepository? = null

        fun get(context: Context): LearningRepository =
            instance ?: synchronized(this) {
                instance ?: LearningRepository(LearningDatabase.get(context)).also { instance = it }
            }
    }
}

internal object LearningSettingsStore {
    private const val PREFS = "eta_learning_settings"
    private const val ENABLED = "enabled"
    private const val AUTO_CAPTURE = "auto_capture"
    private const val AUTO_PROMOTE = "auto_promote"

    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(ENABLED, enabled).apply()
    }

    fun automaticCaptureEnabled(context: Context): Boolean =
        isEnabled(context) && preferences(context).getBoolean(AUTO_CAPTURE, true)

    fun setAutomaticCaptureEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(AUTO_CAPTURE, enabled).apply()
    }

    /** 首版固定关闭；任何规则仍需用户确认，避免静默污染长期记忆。 */
    fun automaticPromotionEnabled(context: Context): Boolean =
        isEnabled(context) && preferences(context).getBoolean(AUTO_PROMOTE, false)

    fun setAutomaticPromotionEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(AUTO_PROMOTE, enabled).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
