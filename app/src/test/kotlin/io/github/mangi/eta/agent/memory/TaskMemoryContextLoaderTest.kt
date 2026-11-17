package io.github.mangi.eta.agent.memory

import android.app.Application
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.fork.learning.LearningRuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TaskMemoryContextLoaderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    companion object {
        @AfterClass @JvmStatic fun close() { LearningDatabase.closeForTests() }
    }

    @Test fun unreadableScopeDoesNotDiscardOtherMemoryOrExperience() = runBlocking(Dispatchers.IO) {
        SettingsDataStore.init(context)
        AgentMemoryRepository.resetForTests(context)
        SettingsDataStore.updateSettings { it.copy(memoryEnabled = true) }
        context.getSharedPreferences("eta_learning_settings", 0).edit().putBoolean("enabled", true).commit()
        AgentMemoryRepository.replaceAll(AgentMemoryScope.GLOBAL, "# 核心记忆\n默认中文回答")
        val badScope = AgentMemoryScope.project("oversized")
        val badFile = java.io.File(context.filesDir, "memory/scopes/project/oversized/MEMORY.md")
        badFile.parentFile!!.mkdirs()
        badFile.writeText("x".repeat(1024 * 1024 + 1))
        LearningDatabase.get(context).learningDao().insertRule(LearningRuleEntity(
            "failure-test", "procedure", "Gradle", "Gradle 先查看日志", 1, "active",
            createdAt = 1, updatedAt = 1, scope = "global"))
        try {
            val result = TaskMemoryContextLoader.build(context, listOf(AgentMemoryScope.GLOBAL, badScope), "Gradle", 128000)
            assertTrue(result.coreContent.contains("默认中文回答"))
            assertTrue(result.coreContent.contains("Gradle 先查看日志"))
            assertFalse(result.sourceRevisions.containsKey(badScope.key))
        } finally {
            badFile.delete()
        }
    }

    @Test fun experienceRemainsIndependentWhenFactualMemoryIsOffAndChildQueriesReselectWithinOwnership() = runBlocking(Dispatchers.IO) {
        SettingsDataStore.init(context)
        AgentMemoryRepository.resetForTests(context)
        SettingsDataStore.updateSettings { it.copy(memoryEnabled = false) }
        context.getSharedPreferences("eta_learning_settings", 0).edit().putBoolean("enabled", true).commit()
        val dao = LearningDatabase.get(context).learningDao()
        suspend fun insert(id: String, scope: String, body: String) = dao.insertRule(LearningRuleEntity(
            id, "procedure", id, body, 1, "active", createdAt = 1, updatedAt = 1, scope = scope))
        insert("gradle", "project:a", "Gradle 超时后先检查代理日志")
        insert("apk", "project:a", "APK 安装前先检查签名")
        insert("private", "project:b", "Gradle 不属于当前项目的私有配置")
        val scopes = listOf(AgentMemoryScope.GLOBAL, AgentMemoryScope.project("a"))
        val parent = TaskMemoryContextLoader.build(context, scopes, "Gradle", 128000)
        assertFalse(parent.enabled)
        assertTrue(parent.coreContent.isBlank())
        assertTrue(parent.learnedContent.contains("代理日志"))
        assertFalse(parent.learnedContent.contains("私有配置"))
        assertFalse(parent.learnedContent.contains("APK"))
        val child = TaskMemoryContextLoader.build(context, scopes, "APK", 128000)
        assertTrue(child.learnedContent.contains("签名"))
        assertFalse(child.learnedContent.contains("Gradle"))
        assertTrue(TaskMemoryContextLoader.build(context, scopes, "", 128000).learnedContent.isBlank())
        assertTrue(child.selectedEntries.sumOf { it.length + 2 } <= TaskMemorySelector.budget(128000))
    }
}
