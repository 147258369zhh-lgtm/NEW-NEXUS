package io.github.mangi.eta.ui.screens.memory

import android.app.Application
import androidx.compose.runtime.*
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.ui.screens.backup.*
import io.github.mangi.eta.ui.screens.metrics.*
import kotlinx.coroutines.*
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
class BackupMetricsPresentationTest {
    @Test fun automaticallySelectedDirectoryUsesAReadablePath() {
        assertEquals(
            "Pictures / EtaBackup",
            backupDirectoryLabel("content://com.android.externalstorage.documents/tree/primary%3APictures%2FEtaBackup"),
        )
        assertEquals("选择可写文件夹后启用自动备份", backupDirectoryLabel(""))
    }

    @Test fun importSpinnerAndProgressBelongToImportAndCanBeCancelled() {
        val context = RuntimeEnvironment.getApplication()
        val vm = BackupViewModel()
        MemoryLearningPresentationTest.Panel {
            LaunchedEffect(Unit) { vm.start(BackupOperation.IMPORT) { report -> report(io.github.mangi.eta.data.repository.EtaBackupProgress("extract")); awaitCancellation() } }
            DataBackupScreen(context, {}, { _, _, _, _ -> error("unused") }, { _, _, _ -> error("unused") }, model = vm)
        }.use { panel ->
            panel.await { panel.texts().contains("读取并解包备份") }
            assertTrue(panel.texts().contains("正在校验或恢复"))
            assertFalse(panel.texts().contains("正在导出"))
            panel.capture("backup-import-progress")
            panel.click("取消")
            panel.await { panel.texts().contains("操作已取消") }
            assertNull(vm.operation)
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h720dp-xhdpi")
    fun backupOptionsUseScrollableExistingDialogsWithLargeText() {
        val context = RuntimeEnvironment.getApplication()
        MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.3f) {
            DataBackupScreen(context, {}, { _, _, _, _ -> error("unused") }, { _, _, _ -> error("unused") })
        }.use { panel ->
            panel.await { panel.texts().contains("导出数据") }
            panel.capture("backup-narrow-dark")
            panel.click("导出数据")
            panel.await { panel.texts().contains("选择位置") }
            panel.capture("backup-export-options-narrow-large-font")
        }
    }

    @Test fun metricsOverviewAndTraceHaveRealRowsAndReadableHierarchy() {
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
            android.content.ComponentName(RuntimeEnvironment.getApplication(), "androidx.room.MultiInstanceInvalidationService"), android.os.Binder())
        val context = RuntimeEnvironment.getApplication()
        val dao = AgentMetricsDatabase.get(context).dao()
        val now = System.currentTimeMillis()
        val metrics = AgentRunMetrics(rounds = 1, toolCalls = 2, succeededTools = 1, failedTools = 1,
            inputTokens = 12000, outputTokens = 320, inputReportedRounds = 1, outputReportedRounds = 1,
            requestAttempts = 1, uncachedInputTokens = 1000, cacheReadTokens = 11000, cacheWriteTokens = 0,
            billableOutputTokens = 320, billingReportedRounds = 1)
        runBlocking(Dispatchers.IO) {
        dao.putRun(AgentRunMetricsRecord("example", "example", "owner", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "chat", "chat", "", "", "",
            now, now, now, 3100, AgentMetricStatus.SUCCEEDED, 1, 0, metrics, "provider", "model", "示例模型"))
        dao.putModels(listOf(AgentMetricModelEntity("example", 0, "provider", "model", "示例模型", metrics,
            AgentTokenPrice.create("USD", "2", "10", "0.5", "2"), true)))
        dao.putTrace(listOf(
            AgentMetricTraceEntity("example", 0, 0, "request", 1, "", null), AgentMetricTraceEntity("example", 1, 500, "response", 1, "200", null),
            AgentMetricTraceEntity("example", 2, 800, "first_text", 1, "", null), AgentMetricTraceEntity("example", 3, 2500, "assistant", 1, "", null)))
        }
        MemoryLearningPresentationTest.Panel {
            AgentMetricsScreen({}, {}, {})
        }.use { panel ->
            panel.await { panel.texts().contains("缓存命中") && panel.texts().contains("91.7%") }
            panel.capture("metrics-overview")
        }
        MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.2f) {
            AgentMetricDetailScreen("example", {}, {}, {}, {})
        }.use { panel ->
            panel.await { panel.texts().any { it.contains("USD") } }
            panel.capture("metrics-detail-dark-large-font")
            panel.scrollUntil("收到首段文字")
            panel.capture("metrics-trace-dark-large-font")
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h720dp-xhdpi")
    fun metricsFilterExpandsSecondLevelChoicesInsideTheParentDialog() {
        MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.3f) {
            AgentMetricsScreen({}, {}, {})
        }.use { panel ->
            panel.await { panel.texts().contains("运行统计") }
            panel.clickDescription("筛选运行记录")
            panel.await { panel.texts().contains("时间") }
            panel.click("时间")
            panel.await { panel.texts().containsAll(listOf("今天", "近 7 天", "近 30 天")) }
            panel.capture("metrics-filter-inline-narrow-large-font")
            panel.click("近 7 天")
            panel.await { panel.texts().contains("近 7 天") }
        }
    }
}
