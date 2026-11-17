package io.github.mangi.eta.fork.learning

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LearningRepositoryTest {
    private lateinit var database: LearningDatabase
    private lateinit var repository: LearningRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            org.robolectric.RuntimeEnvironment.getApplication(),
            LearningDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = LearningRepository(database, clock = { 100L })
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `concurrent confirmations produce one transition and one feedback row`() = runBlocking {
        val candidate = (repository.propose(
            LearningProposalInput(
                kind = LearningKind.PREFERENCE,
                title = "回答风格",
                content = "用户偏好简短回答",
                evidence = "用户明确要求简短",
            ),
        ) as LearningProposalResult.Created).candidate
        val start = CompletableDeferred<Unit>()

        val results = coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    start.await()
                    repository.confirm(candidate.id)
                },
                async(Dispatchers.IO) {
                    start.await()
                    repository.confirm(candidate.id)
                },
            ).also { start.complete(Unit) }.awaitAll()
        }

        assertEquals(1, results.count { it is LearningMutationResult.Success })
        assertEquals(1, results.count { it is LearningMutationResult.Rejected })
        assertEquals(
            1,
            database.learningDao().feedback(candidate.id).count { it.action == "confirm" },
        )
        assertEquals(
            LearningStatus.CONFIRMED.wireValue,
            database.learningDao().candidate(candidate.id)?.status,
        )
    }

    @Test
    fun `locked setting mutations become requests and temporary lease expires`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        LearningSettingsStore.setEnabled(context, true)
        val baseNow = System.currentTimeMillis()
        LearningSettingLock.lock(context, "test", now = baseNow)
        assertTrue(LearningSettingLock.state(context, now = baseNow).locked)
        LearningSettingsStore.setEnabled(context, false)
        assertTrue(LearningSettingsStore.isEnabled(context))
        assertEquals(1, LearningSettingLock.requests(context).size)

        val leaseNow = System.currentTimeMillis()
        val lease = LearningSettingLock.temporaryUnlock(context, 60_000L, "diagnostic", now = leaseNow)
        assertFalse(lease.locked)
        LearningSettingsStore.setEnabled(context, false)
        assertFalse(LearningSettingsStore.isEnabled(context))
        assertTrue(LearningSettingLock.state(context, now = leaseNow + 60_001L).locked)
        assertTrue(LearningSettingLock.auditTrail(context).any { it.contains("lease_expired") })
    }

    @Test
    fun `concurrent promotion creates one rule version and one success`() = runBlocking {
        val candidate = (repository.propose(
            LearningProposalInput(
                kind = LearningKind.PROCEDURE,
                title = "部署前检查",
                content = "部署前先运行测试",
                evidence = "两次部署失败均缺少测试",
            ),
        ) as LearningProposalResult.Created).candidate
        assertTrue(repository.confirm(candidate.id) is LearningMutationResult.Success)
        val start = CompletableDeferred<Unit>()

        val results = coroutineScope {
            listOf(
                async(Dispatchers.IO) {
                    start.await()
                    repository.promote(candidate.id)
                },
                async(Dispatchers.IO) {
                    start.await()
                    repository.promote(candidate.id)
                },
            ).also { start.complete(Unit) }.awaitAll()
        }

        assertEquals(1, results.count { it is LearningMutationResult.Success })
        assertEquals(1, results.count { it is LearningMutationResult.Rejected })
        val promoted = requireNotNull(database.learningDao().candidate(candidate.id))
        assertEquals(LearningStatus.PROMOTED.wireValue, promoted.status)
        val versions = database.learningDao().ruleVersions(requireNotNull(promoted.promotedRuleId))
        assertEquals(1, versions.count { it.candidateId == candidate.id })
    }

    @Test
    fun `same evidence from different sources is preserved while exact duplicates collapse`() = runBlocking {
        val input = LearningProposalInput(
            kind = LearningKind.FACT,
            title = "项目语言",
            content = "项目使用 Kotlin",
            evidence = "源码文件扩展名是 kt",
            sourceRef = "conversation:a",
        )
        repository.propose(input)
        repository.propose(input.copy(sourceRef = "conversation:b"))
        repository.propose(input.copy(sourceRef = "conversation:a"))

        val candidate = repository.snapshot().candidates.single()
        assertEquals(2, candidate.evidence.size)
        assertEquals(
            setOf("conversation:a", "conversation:b"),
            candidate.evidence.map { it.sourceRef }.toSet(),
        )
    }

    private suspend fun createRule(): LearningRuleView {
        val candidate = (repository.propose(LearningProposalInput(LearningKind.PROCEDURE,
            "发布前检查", "每次发布前运行全部测试并等待 30 分钟再发布")) as LearningProposalResult.Created).candidate
        assertTrue(repository.confirm(candidate.id) is LearningMutationResult.Success)
        assertTrue(repository.promote(candidate.id) is LearningMutationResult.Success)
        return repository.snapshot().rules.single()
    }

    @Test fun `changed numeric value remains a separate conflict`() = runBlocking {
        createRule()
        val candidate = (repository.propose(LearningProposalInput(LearningKind.PROCEDURE,
            "发布前检查", "每次发布前运行全部测试并等待 31 分钟再发布")) as LearningProposalResult.Created).candidate
        assertEquals(LearningStatus.BUTLER_APPROVED, candidate.status)
        assertEquals(1, candidate.conflicts.size)
        assertEquals(2, repository.snapshot().candidates.size)
    }

    @Test fun `archiving conflicting rule allows ordinary confirmation`() = runBlocking {
        val rule = createRule()
        val candidate = (repository.propose(LearningProposalInput(LearningKind.PROCEDURE,
            rule.title, "每次发布前运行全部测试并等待 60 分钟再发布")) as LearningProposalResult.Created).candidate
        repository.archiveRule(rule.id)
        assertTrue(repository.snapshot().candidates.first { it.id == candidate.id }.conflicts.isEmpty())
        assertTrue(repository.confirm(candidate.id, expectedConflict = "") is LearningMutationResult.Success)
        assertTrue(repository.promote(candidate.id) is LearningMutationResult.Success)
        assertEquals(candidate.content, repository.snapshot().rules.single().content)
    }

    @Test fun `rule version changes invalidate displayed and previously confirmed overrides`() = runBlocking {
        val rule = createRule()
        val candidate = (repository.propose(LearningProposalInput(LearningKind.PROCEDURE,
            rule.title, "每次发布前运行全部测试并等待 60 分钟再发布")) as LearningProposalResult.Created).candidate
        val dao = database.learningDao()
        val stored = requireNotNull(dao.rule(rule.id))
        dao.updateRule(stored.copy(currentVersion = 2))
        assertTrue(repository.confirm(candidate.id, true, "${rule.id}:1") is LearningMutationResult.Rejected)
        assertTrue(repository.confirm(candidate.id, true, "${rule.id}:2") is LearningMutationResult.Success)
        dao.updateRule(stored.copy(currentVersion = 3))
        assertTrue(repository.promote(candidate.id) is LearningMutationResult.Rejected)
        assertEquals(LearningStatus.CONFLICT.wireValue, dao.candidate(candidate.id)?.status)
        assertTrue(repository.confirm(candidate.id, true, "${rule.id}:3") is LearningMutationResult.Success)
        assertTrue(repository.promote(candidate.id) is LearningMutationResult.Success)
        assertEquals(4, dao.rule(rule.id)?.currentVersion)
        assertEquals(1, dao.allRules().size)
    }

    @Test fun `all conflicting rules must be reviewed before a replacement becomes active`() = runBlocking {
        val rule = createRule()
        val dao = database.learningDao()
        val original = requireNotNull(dao.rule(rule.id))
        dao.insertRule(original.copy(id = "other", title = "另一个发布检查", content = "每次发布前运行全部测试并等待 45 分钟再发布"))
        val candidate = (repository.propose(LearningProposalInput(LearningKind.PROCEDURE,
            rule.title, "每次发布前运行全部测试并等待 60 分钟再发布")) as LearningProposalResult.Created).candidate
        assertEquals(2, candidate.conflicts.size)
        assertTrue(repository.confirm(candidate.id, true, "${rule.id}:1") is LearningMutationResult.Rejected)
        val signature = candidate.conflicts.sortedBy { it.id }.joinToString(",") { "${it.id}:${it.version}" }
        assertTrue(repository.confirm(candidate.id, true, signature) is LearningMutationResult.Success)
        assertTrue(repository.promote(candidate.id) is LearningMutationResult.Success)
        assertEquals(candidate.content, dao.activeRules().single().content)
        assertEquals(2, dao.allRules().size)
    }

    @Test fun `runtime context retrieves related active rules and never cuts an entry`() = runBlocking {
        val rule = createRule()
        val dao = database.learningDao()
        val stored = requireNotNull(dao.rule(rule.id))
        dao.insertRule(stored.copy(id = "pinned", title = "优先经验", pinned = true, content = "先读取当前状态再尝试修改"))
        repository.propose(LearningProposalInput(LearningKind.FACT, "尚未确认", "此项还没有经过用户确认"))
        assertEquals("", repository.context())
        val context = repository.context(query = "读取")
        assertTrue(context.contains("优先经验"))
        assertFalse(context.contains("尚未确认"))
        assertEquals("", repository.context(8, query = "读取"))
        repository.archiveRule(rule.id)
        assertFalse(repository.context().contains(rule.title))
    }

    @Test fun `experience ownership separates duplicate proposals rule keys promotion and retrieval`() = runBlocking {
        suspend fun promote(scope: String): LearningCandidateView {
            val candidate = (repository.propose(LearningProposalInput(LearningKind.PROCEDURE,
                "发布检查", "发布 APK 前先运行 Gradle 测试并检查签名", "发布前运行 Gradle 测试并检查签名", scope = scope)) as LearningProposalResult.Created).candidate
            assertTrue(candidate.conflicts.isEmpty())
            assertTrue(repository.confirm(candidate.id) is LearningMutationResult.Success)
            assertTrue(repository.promote(candidate.id) is LearningMutationResult.Success)
            return candidate
        }
        val global = promote("global")
        val projectA = promote("project:a")
        val projectB = promote("project:b")
        assertEquals(3, database.learningDao().allRules().size)
        assertEquals(1, repository.search("Gradle").size)
        assertEquals(setOf("global", "project:a"), repository.search("Gradle", scopes = setOf("global", "project:a")).map { it.scope }.toSet())
        assertFalse(global.id == projectA.id || projectA.id == projectB.id)
        val duplicate = repository.propose(LearningProposalInput(LearningKind.PROCEDURE,
            "发布检查", "发布 APK 前先运行 Gradle 测试并检查签名", scope = "project:a")) as LearningProposalResult.Existing
        assertEquals(projectA.id, duplicate.candidate.id)
        val changed = (repository.propose(LearningProposalInput(LearningKind.PROCEDURE,
            "发布检查", "发布 APK 前先运行 Gradle 测试并检查版本号", "发布前检查版本号", scope = "project:a")) as LearningProposalResult.Created).candidate
        assertTrue(changed.conflicts.isNotEmpty())
        assertTrue(changed.conflicts.all { conflict -> database.learningDao().rule(conflict.id)?.scope == "project:a" })
    }

    @Test fun `short search queries require an actual match and never include archived rules`() = runBlocking {
        suspend fun insert(id: String, title: String, content: String, status: String = "active") {
            database.learningDao().insertRule(LearningRuleEntity(
                id = id, kind = LearningKind.PROCEDURE.wireValue, title = title, content = content,
                currentVersion = 1, status = status, createdAt = 100L, updatedAt = 100L,
            ))
        }
        insert("root", "根路径检查", "先读取根目录再检查文件")
        insert("install", "安装检查", "先验证 APK 再执行安装")
        insert("unrelated", "部署检查", "提交前运行全部测试")
        insert("archived", "根路径 APK 旧规则", "已经归档的安装经验", "archived")

        assertEquals(listOf("root"), repository.search("根").map { it.id })
        assertEquals(listOf("install"), repository.search("a").map { it.id })
        assertEquals(listOf("install"), repository.search("APK 安装").map { it.id })
        assertTrue(repository.search("z").isEmpty())
        assertTrue(repository.search(" ").isEmpty())
    }

    @Test fun `rollback restores rules archived by the promoted change and keeps every version`() = runBlocking {
        val rule = createRule()
        val dao = database.learningDao()
        val original = requireNotNull(dao.rule(rule.id))
        dao.insertRule(original.copy(id = "other", title = "另一个发布检查",
            content = "每次发布前运行全部测试并等待 45 分钟再发布"))
        val candidate = (repository.propose(LearningProposalInput(LearningKind.PROCEDURE,
            rule.title, "每次发布前运行全部测试并等待 60 分钟再发布")) as LearningProposalResult.Created).candidate
        val signature = candidate.conflicts.sortedBy { it.id }.joinToString(",") { "${it.id}:${it.version}" }
        assertTrue(repository.confirm(candidate.id, true, signature) is LearningMutationResult.Success)
        assertTrue(repository.promote(candidate.id) is LearningMutationResult.Success)
        assertEquals("archived", dao.rule("other")?.status)
        assertEquals(2, dao.rule(rule.id)?.currentVersion)

        val firstVersion = dao.ruleVersions(rule.id).first { it.version == 1 }
        assertTrue(repository.rollback(rule.id, firstVersion.id) is LearningMutationResult.Success)

        assertEquals("active", dao.rule("other")?.status)
        assertEquals("每次发布前运行全部测试并等待 30 分钟再发布", dao.rule(rule.id)?.content)
        val versions = dao.ruleVersions(rule.id)
        assertEquals(listOf(1, 2, 3), versions.map { it.version }.sorted())
        assertEquals("rollback-to-v1", versions.first { it.version == 3 }.reason)
        assertEquals(listOf("promote", "promote", "rollback"), dao.ruleChanges(rule.id).map { it.changeType })
    }

    @Test fun `software butler rejects low value candidates before human approval`() = runBlocking {
        val candidate = (repository.propose(LearningProposalInput(
            kind = LearningKind.PROCEDURE,
            title = "任务状态",
            content = "好的好的好的好的好的好的",
            evidence = "完成",
            sourceRef = "conversation:noise",
        ))) as LearningProposalResult.Created

        assertEquals(LearningStatus.REJECTED, candidate.candidate.status)
        assertTrue(candidate.candidate.reviewSummary.contains("审核拒绝"))
        assertTrue(repository.confirm(candidate.candidate.id) is LearningMutationResult.Rejected)
        assertTrue(repository.snapshot().rules.isEmpty())
    }
}
