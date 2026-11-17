package io.github.mangi.eta.agent.memory

import android.app.Application
import androidx.room.Room
import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.fork.learning.LearningDatabase
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], application = Application::class)
class MemoryAutomationTest {
    @Test fun currentScopeNeverFallsBackToProjectOrUnattachedScope() {
        val projectOnly = listOf("global", "project:alpha")
        assertNull(MemoryAutomaticExtractor.resolveScope("current", projectOnly))
        assertEquals("project:alpha", MemoryAutomaticExtractor.resolveScope("project", projectOnly))
        val meeting = projectOnly + "meeting:room"
        assertEquals("meeting:room", MemoryAutomaticExtractor.resolveScope("current", meeting))
        assertNull(MemoryAutomaticExtractor.resolveScope("project:other", meeting))
    }

    @Test fun projectExperienceIsProposedToTheExperienceLibraryWithItsOwnScope() = runBlocking {
        val item = ExtractedMemory("learning", "project", "long_term", "procedure", "发布检查",
            "发布 APK 前先运行 Gradle 测试并检查签名", "发布前先运行 Gradle 测试并检查签名")
        val scope = requireNotNull(MemoryAutomaticExtractor.resolveScope(item.scope, listOf("global", "project:a", "conversation:one")))
        val proposal = requireNotNull(MemoryAutomaticExtractor.learningProposal(item, scope, "conversation:one"))
        val learning = io.github.mangi.eta.fork.learning.LearningRepository(db, { now })
        val candidate = (learning.propose(proposal) as io.github.mangi.eta.fork.learning.LearningProposalResult.Created).candidate
        assertEquals("project:a", candidate.scope)
        assertTrue(db.memoryDao().entries().isEmpty())
        assertTrue(learning.activeRules(setOf("global", "project:a")).isEmpty())
        assertTrue(learning.confirm(candidate.id) is io.github.mangi.eta.fork.learning.LearningMutationResult.Success)
        assertTrue(learning.promote(candidate.id) is io.github.mangi.eta.fork.learning.LearningMutationResult.Success)
        assertEquals(listOf("project:a"), learning.activeRules(setOf("global", "project:a")).map { it.scope })
        assertTrue(learning.activeRules(setOf("global", "project:b")).isEmpty())
        assertEquals(proposal.copy(sourceRef = "source"), MemoryAutomaticExtractor.learningProposal(item.copy(target = "memory"), scope, "source"))
        assertTrue(MemoryAutomaticExtractor.isExperience(io.github.mangi.eta.fork.learning.LearningKind.FAILURE_FIX))
        assertFalse(MemoryAutomaticExtractor.isExperience(io.github.mangi.eta.fork.learning.LearningKind.FACT))
        assertNull(MemoryAutomaticExtractor.learningProposal(item.copy(kind = "fact"), scope, "source"))
        assertNull(MemoryAutomaticExtractor.learningProposal(item, "project:invalid/id", "source"))
    }
    private lateinit var db: LearningDatabase
    private lateinit var repository: MemoryReviewRepository
    private var now = 1000L
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LearningDatabase::class.java).allowMainThreadQueries().build()
        repository = MemoryReviewRepository(db, { now })
    }
    @After fun close() { db.close() }
    private suspend fun proposal(content: String = "以后请使用中文回答", scope: AgentMemoryScope = AgentMemoryScope.GLOBAL,
        expires: Long? = null, source: String = "conversation:a") =
        requireNotNull(repository.propose(scope, "long_term", "回复语言", content, content, source, expires))

    @Test
    @Config(sdk = [36])
    fun reviewingCandidateDoesNotIntroduceConflictsFromOtherScopes() = runBlocking {
        val firstScope = AgentMemoryScope.project("review-a")
        val otherScope = AgentMemoryScope.project("review-b")
        val other = proposal("以后请使用英文回答", scope = otherScope)
        assertNull(repository.decide(other.id, other.updatedAt, "confirm"))
        val candidate = proposal(scope = firstScope)
        db.memoryDao().update(candidate.copy(status = "butler_review"))
        assertNull(repository.review(candidate.id, candidate.updatedAt))
        var reviewed = db.memoryDao().entry(candidate.id)!!
        assertEquals("", reviewed.conflictIds)
        db.memoryDao().update(reviewed.copy(status = "butler_review"))
        assertNull(repository.decide(reviewed.id, reviewed.updatedAt, "confirm"))
        reviewed = db.memoryDao().entry(candidate.id)!!
        assertEquals("active", reviewed.status)
        assertEquals("", reviewed.conflictIds)
        assertEquals("active", db.memoryDao().entry(other.id)!!.status)
    }

    @Test fun candidatesAreExcludedUntilConfirmedAndNeverCrossScopes() = runBlocking {
        val scope = AgentMemoryScope.project("a")
        val entry = proposal(scope = scope)
        assertTrue(repository.active(listOf(scope)).isEmpty())
        assertNull(repository.decide(entry.id, entry.updatedAt, "confirm"))
        assertEquals(1, repository.active(listOf(scope)).size)
        assertTrue(repository.active(listOf(AgentMemoryScope.GLOBAL, AgentMemoryScope.project("b"))).isEmpty())
        assertEquals(1, repository.search(listOf(scope), "回复语言").size)
    }
    @Test fun parallelEquivalentProposalsProduceOneCandidateAndRejectionStaysRejected() = runBlocking {
        coroutineScope { List(8) { async(Dispatchers.IO) { proposal() } }.awaitAll() }
        assertEquals(1, db.memoryDao().entries().size)
        val entry = db.memoryDao().entries().single()
        assertNull(repository.decide(entry.id, entry.updatedAt, "reject"))
        proposal()
        assertEquals("rejected", db.memoryDao().entries().single().status)
    }
    @Test fun conflictingValueRequiresExplicitDecisionAndSupersedesOldValue() = runBlocking {
        val old = proposal()
        repository.decide(old.id, old.updatedAt, "confirm")
        val changed = proposal("以后请使用英文回答")
        assertEquals("butler_approved", changed.status)
        assertTrue(changed.conflictIds.isNotBlank())
        assertNotNull(repository.decide(changed.id, changed.updatedAt, "confirm"))
        val current = requireNotNull(db.memoryDao().entry(changed.id))
        assertNull(repository.decide(current.id, current.updatedAt, "replace", current.conflictIds))
        assertEquals("superseded", db.memoryDao().entry(old.id)?.status)
        assertEquals("以后请使用英文回答", repository.active(listOf(AgentMemoryScope.GLOBAL)).single().content)
    }
    @Test fun concurrentFactIntroducesConflictBeforeActivation() = runBlocking {
        val first = proposal()
        val second = proposal("以后请使用英文回答")
        repository.decide(first.id, first.updatedAt, "confirm")
        assertNotNull(repository.decide(second.id, second.updatedAt, "confirm"))
        assertEquals("conflict", db.memoryDao().entry(second.id)?.status)
    }
    @Test fun expiredFactsAreNotInjectedOrReturnedFromSearch() = runBlocking {
        val entry = proposal(scope = AgentMemoryScope.task("a"), expires = 2000)
        repository.decide(entry.id, entry.updatedAt, "confirm")
        now = 3000
        assertTrue(repository.active(listOf(AgentMemoryScope.task("a"))).isEmpty())
        assertEquals("", repository.context(listOf(AgentMemoryScope.task("a"))))
    }
    @Test fun parserRequiresVerbatimEvidenceAndDropsFabricatedOrSecretFacts() {
        val source = "以后请使用中文回答，先给结论再解释。"
        val items = JSONArray()
        fun add(content: String, evidence: String) { items.put(JSONObject().put("target", "memory").put("scope", "global")
            .put("topic", "回复语言").put("content", content).put("evidence", evidence)) }
        add("用户偏好中文回答", "以后请使用中文回答")
        add("用户喜欢英文回答", "用户喜欢英文回答")
        add("password=secret-value", "以后请使用中文回答")
        assertEquals(1, MemoryAutomaticExtractor.parse(JSONObject().put("items", items).toString(), source).size)
    }
    @Test fun optOutAndCredentialsNeverBecomeCandidates() = runBlocking {
        assertFalse(MemoryAutomationPolicy.allowed("这段话不要保存到记忆"))
        assertNull(repository.propose(AgentMemoryScope.GLOBAL, "core", "秘密", "password=not-for-memory", "用户原话", "test"))
        assertNull(repository.propose(AgentMemoryScope.GLOBAL, "core", "禁止", "不要记住我的这个习惯", "用户原话", "test"))
    }
    @Test fun dailyBudgetCountsRetriesAndJobsRecoverAfterProcessDeath() = runBlocking {
        val dao = db.memoryDao()
        dao.insertJob(MemoryJob("a", "source", "global", "text", now, status = "running", attempts = 3))
        dao.insertJob(MemoryJob("b", "source", "global", "text", now, attempts = 2))
        assertEquals(5, dao.attemptsSince(now - 1))
        dao.recoverJobs()
        assertEquals("queued", dao.nextJob()?.status)
    }

    @Test fun similarWordingWithDifferentNumbersAndSignsIsNotDeduplicated() = runBlocking {
        val old = proposal("项目每次运行测试后需要等待 30 分钟，然后再提交所有文件")
        repository.decide(old.id, old.updatedAt, "confirm")
        val changed = proposal("项目每次运行测试后需要等待 31 分钟，然后再提交所有文件")
        assertNotEquals(old.id, changed.id)
        assertEquals("butler_approved", changed.status)
        assertTrue(changed.conflictIds.isNotBlank())
        assertFalse(MemoryAutomationPolicy.equivalent("项目温度阈值是 -10 度", "项目温度阈值是 10 度"))
        assertFalse(MemoryAutomationPolicy.equivalent("文件位置是 /Data/Cache", "文件位置是 /data/cache"))
    }

    @Test fun expiredLocalFactCanBeProposedAgainWithNewEvidence() = runBlocking {
        val scope = AgentMemoryScope.task("local")
        val old = proposal(scope = scope, expires = 2000)
        repository.decide(old.id, old.updatedAt, "confirm")
        now = 3000
        val renewed = proposal(scope = scope, expires = 5000)
        assertNotEquals(old.id, renewed.id)
        assertEquals("butler_approved", renewed.status)
        assertTrue(repository.active(listOf(scope)).isEmpty())
        repository.decide(renewed.id, renewed.updatedAt, "confirm")
        assertEquals(renewed.id, repository.active(listOf(scope)).single().id)
    }

    @Test fun explicitUserCorrectionSupersedesOldFactAndPropagatesToContextAndSearch() = runBlocking {
        val old = proposal("以后请使用英文回答", source = "conversation:old")
        repository.decide(old.id, old.updatedAt, "confirm")
        val corrected = requireNotNull(repository.propose(
            AgentMemoryScope.GLOBAL,
            "long_term",
            "回复语言",
            "以后请使用中文回答",
            "不是英文，而是中文",
            "conversation:new",
            correction = true,
        ))
        assertEquals("active", corrected.status)
        assertEquals(old.id, corrected.supersedes)
        assertEquals("conversation:new", corrected.source)
        assertEquals("superseded", db.memoryDao().entry(old.id)?.status)
        assertEquals("conversation:old", db.memoryDao().entry(old.id)?.source)
        assertEquals(listOf(corrected.id), repository.active(listOf(AgentMemoryScope.GLOBAL)).map { it.id })
        val context = repository.context(listOf(AgentMemoryScope.GLOBAL))
        assertTrue(context.contains("中文"))
        assertFalse(context.contains("英文"))
        assertEquals(corrected.id, repository.search(listOf(AgentMemoryScope.GLOBAL), "回复语言").single().id)
    }

    @Test fun correctionDoesNotCrossScopeBoundaries() = runBlocking {
        val projectA = AgentMemoryScope.project("a")
        val projectB = AgentMemoryScope.project("b")
        val old = proposal("以后请使用英文回答", scope = projectA, source = "conversation:old")
        repository.decide(old.id, old.updatedAt, "confirm")
        val attempt = requireNotNull(repository.propose(
            projectB,
            "long_term",
            "回复语言",
            "以后请使用中文回答",
            "不是英文，而是中文",
            "conversation:other",
            correction = true,
        ))
        assertEquals("butler_approved", attempt.status)
        assertTrue(attempt.conflictIds.isBlank())
        assertEquals(old.id, repository.active(listOf(projectA)).single().id)
        assertTrue(repository.active(listOf(projectB)).isEmpty())
    }

    @Test fun correctionCanRestoreAValueThatWasPreviouslySuperseded() = runBlocking {
        val first = proposal("以后请使用中文回答", source = "conversation:first")
        repository.decide(first.id, first.updatedAt, "confirm")
        val second = requireNotNull(repository.propose(AgentMemoryScope.GLOBAL, "long_term", "回复语言",
            "以后请使用英文回答", "不是中文，而是英文", "conversation:second", correction = true))
        assertEquals("active", second.status)
        assertEquals("superseded", db.memoryDao().entry(first.id)?.status)
        val restored = requireNotNull(repository.propose(AgentMemoryScope.GLOBAL, "long_term", "回复语言",
            "以后请使用中文回答", "不是英文，而是中文", "conversation:third", correction = true))
        assertNotEquals(first.id, restored.id)
        assertEquals("active", restored.status)
        assertEquals("superseded", db.memoryDao().entry(second.id)?.status)
        assertEquals(restored.id, repository.active(listOf(AgentMemoryScope.GLOBAL)).single().id)
        assertEquals("conversation:third", restored.source)
    }

    @Test fun correctionFlagRequiresExplicitUserWording() {
        val source = "我说错了，以后请使用中文回答"
        val item = JSONObject()
            .put("target", "memory")
            .put("scope", "global")
            .put("topic", "回复语言")
            .put("content", "以后请使用中文回答")
            .put("evidence", source)
            .put("correction", true)
        val parsed = MemoryAutomaticExtractor.parse(JSONObject().put("items", JSONArray().put(item)).toString(), source).single()
        assertTrue(parsed.correction)
        assertTrue(MemoryAutomationPolicy.looksLikeCorrection(source))
        assertFalse(MemoryAutomationPolicy.looksLikeCorrection("以后请使用中文回答"))
    }

    @Test fun editingCandidateCannotCreateDuplicateFact() = runBlocking {
        val first = proposal()
        val second = proposal("以后请使用英文回答")
        assertNotNull(repository.edit(second.id, first.content))
        assertEquals(second.content, db.memoryDao().entry(second.id)?.content)
    }

    @Test fun softwareButlerRejectsLowValueMemoryBeforeHumanReview() = runBlocking {
        assertNull(repository.propose(
            AgentMemoryScope.GLOBAL,
            "long_term",
            "任务状态",
            "好的好的好的好的好的好的",
            "完成",
            "conversation:noise",
        ))
        assertTrue(db.memoryDao().entries().isEmpty())
    }

    @Test fun hybridSearchExplainsSignalsAndDegradesExplicitlyWithoutSemanticIndex() = runBlocking {
        val project = AgentMemoryScope.project("release")
        val conversation = AgentMemoryScope.conversation("release-chat")
        now = 1_000_000_000L
        val old = confirmed(project, "发布流程", "周三发布产品并检查回滚", "project:release")
        now += 90L * 86_400_000L
        val recent = confirmed(conversation, "发布准备", "周三发布产品，先通知测试组", "conversation:release-chat")

        val result = repository.searchExplained(listOf(project, conversation), "周三发布", limit = 10)

        assertEquals(MemoryRetrievalMode.KEYWORD_ONLY, result.mode)
        assertEquals(MemorySemanticStatus.UNAVAILABLE, result.semanticStatus)
        assertTrue(result.semanticReason.contains("embedding"))
        assertFalse(result.weights.containsKey("semantic"))
        assertEquals(listOf(recent.id, old.id), result.entries.map { it.entry.id })
        val top = result.entries.first().explanation
        assertNull(top.semantic)
        assertTrue(top.keyword > 0.0)
        assertTrue(top.matchedTerms.contains("周三"))
        assertTrue(top.reasons.any { it.contains("关键词命中") })
        assertTrue(top.reasons.any { it.contains("时间") })
        assertTrue(top.reasons.any { it.contains("作用域") })
        assertTrue(top.freshness > result.entries.last().explanation.freshness)
        assertTrue(top.scope > result.entries.last().explanation.scope)
        assertEquals(conversation.key, result.entries.first().entry.scope)
    }

    @Test fun semanticIndexFailureFallsBackWithoutInventingScores() = runBlocking {
        val scope = AgentMemoryScope.project("semantic")
        val entry = confirmed(scope, "构建命令", "发布前运行 ./gradlew assembleRelease", "project:semantic")
        val failing = object : MemorySemanticScorer {
            override suspend fun scores(query: String, entries: List<MemoryEntry>): Map<String, Double> =
                error("embedding service offline")
        }

        val result = repository.searchExplained(listOf(scope), "assembleRelease", semanticScorer = failing)

        assertEquals(MemoryRetrievalMode.KEYWORD_ONLY, result.mode)
        assertEquals(MemorySemanticStatus.FAILED, result.semanticStatus)
        assertTrue(result.semanticReason.contains("IllegalStateException"))
        assertFalse(result.weights.containsKey("semantic"))
        assertEquals(entry.id, result.entries.single().entry.id)
        assertNull(result.entries.single().explanation.semantic)
        assertEquals("project:semantic", result.entries.single().entry.source)
    }

    @Test fun semanticScoresAreOptionalSignalsRatherThanAReplacementForKeywordProvenance() = runBlocking {
        val scope = AgentMemoryScope.project("semantic-ok")
        val entry = confirmed(scope, "构建命令", "发布前运行 ./gradlew assembleRelease", "project:semantic-ok")
        val scorer = object : MemorySemanticScorer {
            override suspend fun scores(query: String, entries: List<MemoryEntry>): Map<String, Double> =
                entries.associate { it.id to 0.75 }
        }

        val result = repository.searchExplained(listOf(scope), "assembleRelease", semanticScorer = scorer)

        assertEquals(MemoryRetrievalMode.HYBRID, result.mode)
        assertEquals(MemorySemanticStatus.AVAILABLE, result.semanticStatus)
        assertEquals(0.45, result.weights.getValue("semantic"), 0.0001)
        assertEquals(entry.id, result.entries.single().entry.id)
        assertEquals(0.75, result.entries.single().explanation.semantic!!, 0.0001)
        assertEquals("project:semantic-ok", result.entries.single().entry.source)
    }

    private suspend fun confirmed(
        scope: AgentMemoryScope,
        topic: String,
        content: String,
        source: String,
    ): MemoryEntry {
        val proposed = requireNotNull(repository.propose(scope, "long_term", topic, content, content, source))
        assertNull(repository.decide(proposed.id, proposed.updatedAt, "confirm"))
        return requireNotNull(db.memoryDao().entry(proposed.id))
    }
}
