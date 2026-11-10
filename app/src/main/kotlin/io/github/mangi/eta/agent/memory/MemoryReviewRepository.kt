package io.github.mangi.eta.agent.memory

import androidx.room.withTransaction
import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.fork.learning.LearningPolicy
import io.github.mangi.eta.fork.learning.SedimentReviewPolicy
import java.util.UUID

/** Candidates become retrievable only after review. Markdown remains an independent editable source. */
internal class MemoryReviewRepository(private val db: LearningDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao = db.memoryDao()
    suspend fun propose(scope: AgentMemoryScope, layer: String, topic: String, content: String,
        evidence: String, source: String, expiresAt: Long? = null): MemoryEntry? = db.withTransaction {
        val combined = "$topic\n$content\n$evidence"
        if (!MemoryAutomationPolicy.allowed(combined) || MemoryAutomationPolicy.sensitive(combined)) return@withTransaction null
        val safeTopic = MemoryAutomationPolicy.safe(topic, 100)
        val safeContent = MemoryAutomationPolicy.safe(content, 1200)
        val safeEvidence = MemoryAutomationPolicy.safe(evidence, 600)
        if (safeTopic.isBlank() || safeContent.length < 4) return@withTransaction null
        val now = clock()
        val entries = dao.entries().filter { it.scope == scope.key }
        val duplicate = entries.firstOrNull {
            MemoryAutomationPolicy.equivalent(it.content, safeContent) &&
                (it.expiresAt == null || it.expiresAt > now || it.status in setOf("rejected", "archived", "superseded"))
        }
        if (duplicate != null) return@withTransaction duplicate
        val conflicts = conflicts(entries, safeTopic, safeContent, now)
        val review = SedimentReviewPolicy.reviewMemory(
            topic = safeTopic,
            content = safeContent,
            evidence = safeEvidence,
            source = source,
            confidence = 0.7,
            hasConflict = conflicts.isNotEmpty(),
        )
        if (!review.approved) return@withTransaction null
        val reviewedEvidence = buildString {
            append(review.summary)
            if (safeEvidence.isNotBlank()) append('\n').append(safeEvidence)
        }.take(800)
        val entry = MemoryEntry(UUID.randomUUID().toString(), scope.key,
            layer.takeIf { it in setOf("core", "project", "task", "long_term", "working", "other") } ?: "long_term",
            safeTopic, safeContent, reviewedEvidence, MemoryAutomationPolicy.safe(source, 256),
            LearningPolicy.evidenceHash(scope.key + MemoryAutomationPolicy.dedupKey(safeContent)),
            "butler_approved", review.score.coerceIn(0.0, 1.0), now, now, expiresAt,
            signature(conflicts))
        dao.insert(entry)
        entry
    }

    suspend fun decide(id: String, expectedUpdatedAt: Long, action: String, expectedConflicts: String = ""): String? = db.withTransaction {
        var entry = dao.entry(id) ?: return@withTransaction "条目已不存在，请刷新"
        if (entry.updatedAt != expectedUpdatedAt) return@withTransaction "条目已更新，请重新审阅"
        val now = clock().coerceAtLeast(entry.updatedAt + 1)
        if (entry.status in setOf("pending", "conflict", "butler_review")) {
            val peers = dao.entries().filter { it.id != entry.id }
            val currentConflicts = conflicts(peers, entry.topic, entry.content, now)
            val review = SedimentReviewPolicy.reviewMemory(
                topic = entry.topic,
                content = entry.content,
                evidence = entry.evidence,
                source = entry.source,
                confidence = entry.confidence,
                hasConflict = currentConflicts.isNotEmpty(),
            )
            if (!review.approved) {
                dao.update(entry.copy(status = "rejected", updatedAt = now))
                return@withTransaction "软件管家审核未通过：${review.reasonCodes.joinToString("；")}"
            }
            entry = entry.copy(
                status = "butler_approved",
                confidence = review.score.coerceIn(0.0, 1.0),
                conflictIds = signature(currentConflicts),
                updatedAt = now,
            )
            dao.update(entry)
        }
        if (action == "archive" && entry.status == "active" ||
            action == "reject" && entry.status in setOf("butler_approved", "pending", "conflict")
        ) {
            dao.update(entry.copy(status = if (action == "archive") "archived" else "rejected", updatedAt = now))
            return@withTransaction null
        }
        if (action !in setOf("confirm", "replace", "keep_both") || entry.status !in setOf("butler_approved", "pending", "conflict"))
            return@withTransaction "该条目当前不能执行此操作"
        if (entry.expiresAt != null && entry.expiresAt <= now) return@withTransaction "条目已过期，请重新提取"
        val conflicts = conflicts(dao.entries().filter { it.scope == entry.scope && it.id != entry.id }, entry.topic, entry.content, now)
        val actual = signature(conflicts)
        if (actual.isNotEmpty() && (action == "confirm" || expectedConflicts != actual)) {
            dao.update(entry.copy(status = "conflict", conflictIds = actual, updatedAt = now))
            return@withTransaction "发现新增或变化的冲突，请对照后重新选择"
        }
        if (action == "replace") conflicts.forEach { dao.update(it.copy(status = "superseded", updatedAt = now)) }
        dao.update(entry.copy(status = "active", updatedAt = now, conflictIds = "", supersedes = if (action == "replace") conflicts.joinToString(",") { it.id } else ""))
        null
    }

    suspend fun review(id: String, expectedUpdatedAt: Long): String? = db.withTransaction {
        val entry = dao.entry(id) ?: return@withTransaction "条目已不存在，请刷新"
        if (entry.updatedAt != expectedUpdatedAt) return@withTransaction "条目已更新，请重新审阅"
        if (entry.status !in setOf("pending", "conflict", "butler_review")) {
            return@withTransaction "该条目不需要软件管家复核"
        }
        val now = clock().coerceAtLeast(entry.updatedAt + 1)
        val peers = dao.entries().filter { it.id != entry.id }
        val currentConflicts = conflicts(peers, entry.topic, entry.content, now)
        val review = SedimentReviewPolicy.reviewMemory(
            topic = entry.topic,
            content = entry.content,
            evidence = entry.evidence.substringAfter('\n', entry.evidence).trim(),
            source = entry.source,
            confidence = entry.confidence,
            hasConflict = currentConflicts.isNotEmpty(),
        )
        dao.update(entry.copy(
            status = if (review.approved) "butler_approved" else "rejected",
            confidence = review.score.coerceIn(0.0, 1.0),
            conflictIds = signature(currentConflicts),
            updatedAt = now,
        ))
        if (review.approved) null else "软件管家审核未通过：${review.reasonCodes.joinToString("；")}"
    }

    suspend fun edit(id: String, content: String): String? = db.withTransaction {
        val entry = dao.entry(id) ?: return@withTransaction "条目已不存在"
        if (entry.status !in setOf("butler_approved", "pending", "conflict")) return@withTransaction "只有管家审核通过的条目可以修改"
        if (content.trim().length !in 4..1200 || MemoryAutomationPolicy.sensitive(content) || !MemoryAutomationPolicy.allowed(content)) return@withTransaction "内容无效或包含敏感字段"
        val safe = MemoryAutomationPolicy.safe(content, 1200)
        val peers = dao.entries().filter { it.scope == entry.scope && it.id != id }
        if (peers.any { MemoryAutomationPolicy.equivalent(it.content, safe) }) return@withTransaction "已存在相同记忆，请处理已有条目"
        val conflicts = conflicts(peers, entry.topic, safe, clock())
        val review = SedimentReviewPolicy.reviewMemory(
            topic = entry.topic,
            content = safe,
            evidence = entry.evidence.substringAfter('\n', entry.evidence).trim(),
            source = entry.source,
            confidence = entry.confidence,
            hasConflict = conflicts.isNotEmpty(),
        )
        if (!review.approved) return@withTransaction "软件管家审核未通过：${review.reasonCodes.joinToString("；")}"
        dao.update(entry.copy(content = safe, status = "butler_approved", confidence = review.score.coerceIn(0.0, 1.0),
            fingerprint = LearningPolicy.evidenceHash(entry.scope + MemoryAutomationPolicy.dedupKey(safe)),
            conflictIds = signature(conflicts), updatedAt = clock().coerceAtLeast(entry.updatedAt + 1)))
        null
    }

    suspend fun active(scopes: List<AgentMemoryScope>): List<MemoryEntry> {
        val keys = scopes.map { it.key }.toSet(); val now = clock()
        return dao.entries().filter { it.scope in keys && it.status == "active" && (it.expiresAt == null || it.expiresAt > now) }
    }

    suspend fun context(scopes: List<AgentMemoryScope>, maxChars: Int = 4000): String = active(scopes)
        .sortedBy { if (it.layer == "core") 0 else 1 }.joinToString("\n") { "[${it.scope}] ${it.topic}: ${it.content}" }.take(maxChars)

    suspend fun search(scopes: List<AgentMemoryScope>, query: String, limit: Int = 12): List<MemoryEntry> {
        val needle = MemoryAutomationPolicy.normalize(query)
        if (needle.isEmpty()) return emptyList()
        return active(scopes).map { it to (if (MemoryAutomationPolicy.normalize(it.topic + it.content).contains(needle)) 2.0 else MemoryAutomationPolicy.similarity(it.topic + it.content, query)) }
            .filter { it.second >= 0.08 }.sortedByDescending { it.second }.take(limit.coerceIn(1, 30)).map { it.first }
    }

    fun conflictEntries(entry: MemoryEntry, entries: List<MemoryEntry>): List<MemoryEntry> =
        conflicts(entries.filter { it.scope == entry.scope && it.id != entry.id }, entry.topic, entry.content, clock())

    private fun conflicts(entries: List<MemoryEntry>, topic: String, content: String, now: Long) = entries.filter {
        it.status == "active" && (it.expiresAt == null || it.expiresAt > now) &&
            (MemoryAutomationPolicy.normalize(it.topic) == MemoryAutomationPolicy.normalize(topic) || MemoryAutomationPolicy.similarity(it.content, content) >= 0.56) &&
            !MemoryAutomationPolicy.equivalent(it.content, content)
    }
    private fun signature(entries: List<MemoryEntry>) = entries.sortedBy { it.id }.joinToString(",") { "${it.id}@${it.updatedAt}" }
}
