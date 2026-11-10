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
    private lateinit var db: LearningDatabase
    private lateinit var repository: MemoryReviewRepository
    private var now = 1000L
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), LearningDatabase::class.java).allowMainThreadQueries().build()
        repository = MemoryReviewRepository(db) { now }
    }
    @After fun close() { db.close() }
    private suspend fun proposal(content: String = "以后请使用中文回答", scope: AgentMemoryScope = AgentMemoryScope.GLOBAL, expires: Long? = null) =
        requireNotNull(repository.propose(scope, "long_term", "回复语言", content, content, "conversation:a", expires))

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
}
