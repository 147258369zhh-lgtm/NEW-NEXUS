package io.github.mangi.eta.agent.skill

import android.app.Application
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.fork.learning.LearningDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SkillEvolutionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val raw = """{"reusable":true,"key":"project-report","title":"项目报告","scenario":"对项目文件进行检查并保存结构化报告","triggers":"同类项目报告任务","inputs":"用户授权的项目目录","steps":[{"instruction":"读取文件并核对项目内容","tools":["read_file"]},{"instruction":"整理报告并保存到授权位置","tools":["write_file"]}],"outputs":"结构化项目报告","verification":"重新读取报告核对关键内容","cautions":"不覆盖已有文件，保持来源可追溯"}"""
    private val source = SkillExperience("run-1", "global", "source", "read_file\nwrite_file", 1)
    @Before fun setup() {
        SkillRuntime.resetForTests()
        LearningDatabase.closeForTests(); EtaDatabase.closeForTests()
        context.deleteDatabase("eta_learning.db"); context.deleteDatabase("eta.db")
        context.getSharedPreferences("skill_evolution", 0).edit().clear().commit()
        context.getSharedPreferences("skill-publication", 0).edit().clear().commit()
    }
    @After fun close() { LearningDatabase.closeForTests(); EtaDatabase.closeForTests() }

    @Test fun parserCreatesScopedStableIdAndOnlyVerifiedTools() {
        val recipe = SkillEvolution.parse(raw, source)!!
        assertTrue(recipe.id.startsWith("auto-")); assertEquals("read_file\nwrite_file", recipe.tools)
        assertTrue(recipe.body.contains("成功检查")); assertTrue(recipe.body.contains("适用边界"))
        assertEquals(recipe.id, SkillEvolution.parse(raw, source.copy(runId = "another"))!!.id)
        assertNotEquals(recipe.id, SkillEvolution.parse(raw, source.copy(scope = "project:one"))!!.id)
    }

    @Test fun unverifiedToolsAndCredentialBearingRecipesAreRejected() {
        assertTrue(runCatching { SkillEvolution.parse(raw.replace("write_file", "terminal_exec"), source) }.isFailure)
        assertTrue(runCatching { SkillEvolution.parse(raw.replace("不覆盖已有文件，保持来源可追溯", "api_key=sk-abcdefghijklmnopqrstuvwxyz0123456789"), source) }.isFailure)
        assertNull(SkillEvolution.parse("{\"reusable\":false}", source))
    }

    @Test fun extractionBudgetCountsAttemptsEvenForOldQueuedEvidence() {
        SkillEvolution.setDailyLimit(context, 3)
        repeat(3) { assertTrue(SkillEvolution.reserveReview(context, 100000000)) }
        assertFalse(SkillEvolution.reserveReview(context, 100000001))
        assertTrue(SkillEvolution.reserveReview(context, 200000000))
    }

    @Test fun publishingRequiresThreeIndependentEvidenceAndDetectsStaleEdits() = runBlocking {
        val dao = LearningDatabase.get(context).skillEvolutionDao(); val recipe = SkillEvolution.parse(raw, source)!!
        dao.save(recipe)
        dao.insert(source.copy(recipeId = recipe.id, state = "accepted"))
        dao.insert(source.copy(recipeId = recipe.id, state = "accepted"))
        assertEquals(1, dao.evidence(recipe.id).size)
        assertTrue(runCatching { SkillEvolution.publish(context, recipe.id, recipe.updatedAt, recipe.body) }.isFailure)
        dao.insert(source.copy(runId = "run-2", recipeId = recipe.id, state = "accepted"))
        dao.insert(source.copy(runId = "run-3", recipeId = recipe.id, state = "accepted"))
        assertTrue(runCatching { SkillEvolution.publish(context, recipe.id, recipe.updatedAt - 1, recipe.body) }.isFailure)
        SkillEvolution.publish(context, recipe.id, recipe.updatedAt, recipe.body)
        val active = dao.recipe(recipe.id)!!
        assertEquals("active", active.state); assertEquals(1, active.version)
        val index = SkillRuntime.createIndexService(context)
        assertTrue(index.listInstalledSkills().any { it.id == recipe.id && it.enabled })
        index.setSkillEnabled(recipe.id, false)
        dao.save(active.copy(state = "paused"))
        assertTrue(runCatching { SkillEvolution.publish(context, recipe.id, active.updatedAt, recipe.body + "\n重新检查输出。") }.isFailure)
        for (i in 4..6) dao.insert(source.copy(runId = "run-$i", recipeId = recipe.id, state = "accepted", createdAt = active.updatedAt + i))
        SkillEvolution.publish(context, recipe.id, active.updatedAt, recipe.body + "\n重新检查输出。")
        assertEquals(2, dao.recipe(recipe.id)!!.version); assertEquals(2, dao.versions(recipe.id).size)
        assertTrue(SkillVersionService(context).listForSkill(recipe.id).isNotEmpty())
    }

    @Test fun approvedCandidatePublishesAutomaticallyOnlyWhenTheSettingIsEnabled() = runBlocking {
        val dao = LearningDatabase.get(context).skillEvolutionDao(); val recipe = SkillEvolution.parse(raw, source)!!
        dao.save(recipe)
        dao.insert(source)
        dao.insert(source.copy(runId = "run-2", recipeId = recipe.id, state = "accepted"))
        dao.insert(source.copy(runId = "run-3", recipeId = recipe.id, state = "accepted"))
        SkillEvolution.acceptProposal(context, source, recipe)
        val approved = dao.recipe(recipe.id)!!
        assertEquals("butler_approved", approved.state)

        SkillEvolution.setAutoPublish(context, false)
        io.github.mangi.eta.data.repository.EtaBackupOperations.exclusive {
            SkillEvolution.autoPublishApprovedLocked(context, dao)
        }
        assertEquals("butler_approved", dao.recipe(recipe.id)!!.state)

        SkillEvolution.setAutoPublish(context, true)
        io.github.mangi.eta.data.repository.EtaBackupOperations.exclusive {
            SkillEvolution.autoPublishApprovedLocked(context, dao)
        }
        val active = dao.recipe(recipe.id)!!
        assertEquals("active", active.state)
        assertEquals(1, active.version)
        assertTrue(SkillEvolution.notice(context).orEmpty().contains("已自动发布"))
        assertTrue(SkillRuntime.createIndexService(context).listInstalledSkills().any { it.id == recipe.id && it.enabled })
    }

    @Test fun publicationJournalRecoversExactInstalledBody() = runBlocking {
        val dao = LearningDatabase.get(context).skillEvolutionDao(); val recipe = SkillEvolution.parse(raw, source)!!
        dao.save(recipe)
        val body = recipe.body + "\n来源：测试验证。"
        SkillPublicationJournal.prepare(context, recipe, recipe.body, body)
        val result = SkillAuthoringService(SkillRuntime.createIndexService(context), SkillRuntime.createPackageInstaller(context)).manage(
            JSONObject().put("action", "create").put("skillId", recipe.id).put("description", recipe.description).put("bodyMarkdown", body))
        assertTrue(result.toString(), result.getBoolean("ok"))
        SkillPublicationJournal.recover(context)
        assertEquals("active", dao.recipe(recipe.id)!!.state); assertEquals(result.getString("revision"), dao.recipe(recipe.id)!!.revision)
        assertEquals(1, dao.versions(recipe.id).size)
        SkillPublicationJournal.recover(context)
        assertEquals(1, dao.versions(recipe.id).size)
    }

    @Test fun mergeMovesEvidenceWithoutPublishingAndKeepsScopeBoundary() = runBlocking {
        val dao = LearningDatabase.get(context).skillEvolutionDao(); val original = SkillEvolution.parse(raw, source)!!
        val similar = original.copy(id = "auto-similar")
        dao.save(original); dao.save(similar)
        dao.insert(source.copy(recipeId = original.id, state = "accepted"))
        dao.insert(source.copy(runId = "run-2", recipeId = similar.id, state = "accepted"))
        dao.insert(source.copy(runId = "run-3", recipeId = similar.id, state = "accepted"))
        SkillEvolution.merge(context, similar.id, original.id, similar.updatedAt)
        assertEquals(3, dao.evidence(original.id).size); assertEquals("butler_approved", dao.recipe(original.id)!!.state)
        assertEquals("merged", dao.recipe(similar.id)!!.state); assertEquals(0, dao.recipe(original.id)!!.version)
        val project = original.copy(id = "auto-project", scope = "project:one")
        dao.save(project)
        assertTrue(runCatching { SkillEvolution.merge(context, project.id, original.id, project.updatedAt) }.isFailure)
    }

    @Test fun actualExperienceExtractionWaitsForThreeSourcesAndKeepsPublishedSkillUntilAnUpdateIsApproved() = runBlocking {
        runCatching { androidx.work.WorkManager.getInstance(context) }.getOrElse {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
            androidx.work.WorkManager.getInstance(context)
        }
        val db = LearningDatabase.get(context); val dao = db.skillEvolutionDao()
        io.github.mangi.eta.data.repository.NexusExecutionStore(context).use { executions ->
            var reply = raw
            val runtime = io.github.mangi.eta.agent.runtime.RuntimeModelTaskHarness(context, executions) {
                object : io.github.mangi.eta.agent.model.AgentProviderClient {
                    override val id = "skill-extraction-fixture"
                    override val capabilities = io.github.mangi.eta.agent.model.OpenAiChatCompletionsProvider.capabilities
                    override fun complete(request: io.github.mangi.eta.agent.model.ProviderRequest,
                        runController: io.github.mangi.eta.agent.runtime.AgentRunController,
                        onEvent: (io.github.mangi.eta.agent.model.ProviderEvent) -> Unit): io.github.mangi.eta.agent.model.ProviderResponse {
                        assertEquals(0, request.effectiveTools.length())
                        onEvent(io.github.mangi.eta.agent.model.ProviderEvent.RequestStarted)
                        return io.github.mangi.eta.agent.model.ProviderResponse(JSONObject().put("role", "assistant").put("content", reply).put("finish_reason", "stop"))
                    }
                }
            }
            val config = io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "")
            var recipeId = ""
            for (number in 1..3) {
                val runId = "actual-source-$number"
                SkillEvolution.capture(context, runId, "global", "检查项目文件并写入可核对的结构化报告", listOf(
                    io.github.mangi.eta.agent.runtime.AgentEvent.ToolStarted(1, "read", "read_file", "reports/input.md"),
                    io.github.mangi.eta.agent.runtime.AgentEvent.ToolFinished(1, "read", "read_file", "已读取项目内容", 0, 0, true),
                    io.github.mangi.eta.agent.runtime.AgentEvent.ToolStarted(2, "write", "write_file", "reports/output.md"),
                    io.github.mangi.eta.agent.runtime.AgentEvent.ToolFinished(2, "write", "write_file", "已保存并核对报告", 0, 0, true)))
                val captured = dao.experience(runId)!!
                val request = io.github.mangi.eta.agent.runtime.RuntimeModelTasks.request(io.github.mangi.eta.agent.runtime.RuntimeModelTaskKind.SKILL,
                    config, "只从验证的工具结果提炼步骤", captured.digest, captured.runId)
                val result = runtime.run(request, io.github.mangi.eta.agent.runtime.AgentRunController())
                assertTrue(result.error, result.ok)
                val proposal = SkillEvolution.parse(result.content, captured)!!
                recipeId = proposal.id
                SkillEvolution.acceptProposal(context, captured, proposal)
                val candidate = dao.recipe(recipeId)!!
                assertEquals(number, SkillEvolution.evidenceForDraft(context, candidate).size)
                assertEquals(if (number < 3) "collecting" else "butler_approved", candidate.state)
                assertNull(SkillEvolution.runtimeRecipe(context, recipeId))
                assertTrue(SkillRuntime.createIndexService(context).listInstalledSkills().none { it.id == recipeId })
            }
            val candidate = dao.recipe(recipeId)!!
            SkillEvolution.publish(context, recipeId, candidate.updatedAt, candidate.body)
            val published = SkillEvolution.runtimeRecipe(context, recipeId)!!
            assertEquals(1, published.version)
            val installed = SkillRuntime.createIndexService(context).listInstalledSkills().single { it.id == recipeId }
            val installedBody = SkillRuntime.createLoader(context).load(installed, "检查来源")!!.bodyMarkdown
            assertTrue(installedBody.contains("run:actual-source-1"))
            val publishedAt = dao.recipe(recipeId)!!.updatedAt
            for (number in 1..3) {
                val update = source.copy(runId = "update-$number", createdAt = publishedAt + number,
                    digest = JSONObject().put("task", "重新核对项目报告").toString())
                dao.insert(update)
                val proposal = SkillEvolution.parse(raw.replace("write_file", "read_file"), update)!!
                SkillEvolution.acceptProposal(context, update, proposal)
                val draft = dao.recipe(recipeId)!!
                assertEquals(if (number < 3) "update_collecting" else "update_butler_approved", draft.state)
                assertEquals("read_file", draft.tools)
                assertEquals(published.tools, SkillEvolution.runtimeRecipe(context, recipeId)!!.tools)
                assertEquals(published.body, SkillEvolution.runtimeRecipe(context, recipeId)!!.body)
                if (number < 3) assertTrue(runCatching { SkillEvolution.publish(context, recipeId, draft.updatedAt, draft.body) }.isFailure)
            }
            val updated = dao.recipe(recipeId)!!
            SkillEvolution.publish(context, recipeId, updated.updatedAt, updated.body)
            assertEquals(2, SkillEvolution.runtimeRecipe(context, recipeId)!!.version)
            assertEquals("read_file", SkillEvolution.runtimeRecipe(context, recipeId)!!.tools)
            assertEquals(2, dao.versions(recipeId).size)
            assertEquals(3, runtime.requests.size)
        }
    }

    @Test fun lateExtractionCannotResurrectASourceRemovedByRestore() = runBlocking {
        val proposal = SkillEvolution.parse(raw, source)!!
        SkillEvolution.acceptProposal(context, source, proposal)
        assertNull(LearningDatabase.get(context).skillEvolutionDao().recipe(proposal.id))
    }
}
