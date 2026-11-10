package io.github.mangi.eta.ui.screens.tasks

import android.app.Application
import io.github.mangi.eta.agent.skill.*
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.fork.tasks.*
import io.github.mangi.eta.ui.screens.memory.MemoryLearningPresentationTest.Panel
import io.github.mangi.eta.ui.screens.skills.BoundaryToolsScreen
import io.github.mangi.eta.ui.screens.skills.SkillEvolutionScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "zh-rCN-w411dp-h896dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomationPresentationTest {
    @Test fun centerAndWorkflowEditorUseExistingTheme() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        AgentTaskDatabase.closeForTests(); context.deleteDatabase("eta_task.db")
        val db = AgentTaskDatabase.get(context)
        AutomationRepository(db).save(null, "早间行业报告", "获取行业信息并整理报告保存到项目目录", AutomationSpec(listOf(
            WorkflowStep("research", WorkflowStepKind.AGENT, "收集并核对资料", prompt = "搜索公开资料并标注来源"),
            WorkflowStep("report", WorkflowStepKind.AGENT, "生成项目报告", prompt = "根据前一步资料生成报告"),
            WorkflowStep("notify", WorkflowStepKind.NOTIFY, "通知完成"))), null,
            AgentScheduleRule(AgentScheduleKind.DAILY, System.currentTimeMillis(), localHour = 8, localMinute = 0, timeZoneId = "Asia/Shanghai"))
        Panel { AgentTaskCenterScreen {} }.use { p ->
            p.await { p.texts().contains("早间行业报告") }; p.capture("automation-center")
            p.click("早间行业报告"); p.await { p.texts().contains("触发条件") }; p.capture("automation-editor")
            p.scrollUntil("执行流程"); p.await { p.texts().contains("3 步") }; p.capture("automation-workflow")
            p.click("收集并核对资料"); p.await { p.texts().contains("执行控制") }; p.capture("automation-step-editor")
        }
        AgentTaskDatabase.closeForTests()
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h720dp-xhdpi")
    fun candidatesAndPermissionsRemainReadableWithLargeDarkText() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        LearningDatabase.closeForTests(); context.deleteDatabase("eta_learning.db")
        val dao = LearningDatabase.get(context).skillEvolutionDao()
        val recipe = SkillRecipe("auto-demo", "global", "整理项目周报", "核对资料来源，生成带有核对步骤的项目周报。",
            "# 整理项目周报\n\n## 适用场景\n同类项目周报。\n\n## 执行步骤\n1. 读取授权资料并检查来源。\n2. 保存报告并重新读取核对。", "read_file\nwrite_file", state = "review", updatedAt = 1)
        dao.save(recipe)
        repeat(3) { dao.insert(SkillExperience("run-$it", "global", "{\"task\":\"核对项目资料并整理周报\"}", recipe.tools, it.toLong(), "accepted", recipe.id)) }
        Panel(dark = true, fontScale = 1.3f) { SkillEvolutionScreen {} }.use { p ->
            p.await { p.texts().contains("整理项目周报") }; p.capture("skill-candidates-narrow-dark")
            assertTrue(p.texts().any { it.contains("全局") })
            assertFalse(p.texts().any { it.contains("global") })
            p.click("整理项目周报"); p.await { p.texts().any { it.contains("待软件管家复核") } }; p.capture("skill-detail-narrow-dark")
            p.scrollUntil("已验证工具"); p.capture("skill-evidence-narrow-dark")
            assertTrue(p.texts().contains("读取文件"))
            assertTrue(p.texts().contains("写入文件"))
        }
        Panel(dark = true, fontScale = 1.3f) { BoundaryToolsScreen {} }.use { p ->
            p.await { p.texts().contains("全局工具范围") }; p.capture("tool-boundaries-narrow-dark")
        }
        LearningDatabase.closeForTests()
    }
}
