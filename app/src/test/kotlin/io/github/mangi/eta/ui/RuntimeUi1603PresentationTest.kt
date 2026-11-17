package io.github.mangi.eta.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.ConversationQuestionNode
import io.github.mangi.eta.ui.components.ConversationQuestionRail
import io.github.mangi.eta.ui.screens.metrics.AgentMetricDistributionCard
import io.github.mangi.eta.ui.screens.metrics.AgentMetricsHeatmap
import io.github.mangi.eta.ui.screens.metrics.MetricDistribution
import io.github.mangi.eta.ui.screens.metrics.MetricTimeline
import io.github.mangi.eta.ui.screens.metrics.MetricTimelineBucket
import io.github.mangi.eta.ui.voice.DictationWaveform
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Native rendering evidence only: no reference image or pixel equality assertions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "zh-rCN-w390dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RuntimeUi1603PresentationTest {
    @Test
    fun runtimeComponentsRenderOnPhoneInLightAndDarkThemes() {
        listOf(false, true).forEach { dark ->
            Presentation(dark).use { panel ->
                assertTrue(panel.texts().containsAll(listOf("语音输入", "模型 Token 分布", "运行活动", "小时视图")))
                assertTrue(panel.descriptions().contains("聊天时间轴，长按滑动按问题定位"))
                assertTrue(panel.descriptions().any { it.startsWith("模型 Token 分布，已报告") })
                panel.capture(if (dark) "runtime-ui-dark" else "runtime-ui-light")
            }
        }
    }

    @Composable
    private fun RuntimeComponents() {
        val samples = remember {
            floatArrayOf(0f, .12f, -.24f, .48f, -.65f, .82f, -.4f, .25f, -.54f, .36f, -.13f, 0f)
        }
        val questions = remember {
            listOf("今天的计划", "总结运行结果", "检查模型用量", "继续处理下一项").mapIndexed { index, label ->
                ConversationQuestionNode("question-$index", label)
            }
        }
        val timeline = remember {
            val firstHour = LocalDateTime.of(2026, 11, 16, 6, 0)
            val runs = listOf(0, 1, 2, 4, 7, 3, 0, 2, 5, 8, 4, 1, 2, 6, 3, 1, 0, 2)
            MetricTimeline("小时视图", runs.mapIndexed { index, count ->
                val start = firstHour.plusHours(index.toLong())
                val input = count * 1_200L
                val output = count * 180L
                MetricTimelineBucket(
                    start = start, endExclusive = start.plusHours(1), runs = count,
                    input = input, output = output, tokens = input + output, incomplete = false,
                )
            })
        }
        Column(
            Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("语音输入", style = MiuixTheme.textStyles.body1)
                    Text("正在聆听", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    DictationWaveform(
                        active = true, level = { .68f }, samples = { samples },
                        modifier = Modifier.fillMaxWidth().height(126.dp),
                    )
                }
                ConversationQuestionRail(questions, currentIndex = 2, onSelect = {})
            }
            AgentMetricDistributionCard(
                title = "模型 Token 分布",
                items = listOf(
                    MetricDistribution("云端模型", 36_000L),
                    MetricDistribution("本地模型", 18_000L),
                    MetricDistribution("工具模型", 6_000L),
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            AgentMetricsHeatmap(timeline, onSelect = { _, _ -> })
        }
    }

    private inner class Presentation(dark: Boolean) : AutoCloseable {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        private val activity = controller.get()
        private val clock = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        private val recomposer = Recomposer(scope.coroutineContext)
        private val view = ComposeView(activity)

        init {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            view.setParentCompositionContext(recomposer)
            view.setContent {
                AgentAppTheme(
                    AppearanceSettings(
                        themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT,
                        blurEnabled = false,
                    ),
                    applyInterfaceScale = false,
                ) {
                    CompositionLocalProvider(LocalSquircleEnabled provides false) { RuntimeComponents() }
                }
            }
            activity.setContentView(view)
            repeat(45) {
                Snapshot.sendApplyNotifications()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
                clock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
                root().measureAndLayoutForTest()
            }
        }

        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                findRoot(view.getChildAt(index))?.let { return it }
            }
            return null
        }

        private fun root(): ViewRootForTest = findRoot(activity.window.decorView) ?: error("Missing Compose root")
        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::nodes)
        private fun allNodes() = nodes(root().semanticsOwner.unmergedRootSemanticsNode)
        fun texts(): List<String> = allNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }
        fun descriptions(): List<String> = allNodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }

        fun capture(name: String) {
            assertEquals("The evidence must use a 390dp phone width", 390, (view.width / activity.resources.displayMetrics.density).roundToInt())
            assertTrue(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                val output = File("build/1603-visual/$name.png")
                output.parentFile!!.mkdirs()
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                assertTrue("Native evidence PNG was not written", output.isFile && output.length() > 0L)
            } finally {
                bitmap.recycle()
            }
        }

        override fun close() {
            recomposer.cancel()
            scope.cancel()
            controller.pause().stop().destroy()
        }
    }
}
