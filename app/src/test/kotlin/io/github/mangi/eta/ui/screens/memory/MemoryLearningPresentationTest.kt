package io.github.mangi.eta.ui.screens.memory

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import io.github.mangi.eta.agent.memory.MemoryReviewRepository
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.repository.AgentMemoryScope
import io.github.mangi.eta.fork.learning.*
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.model.AgentMemoryUiState
import io.github.mangi.eta.ui.screens.learning.LearningSettingsScreen
import java.io.File
import java.time.Duration
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h896dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemoryLearningPresentationTest {
    @Test fun memoryLibraryShowsCandidatesAndSettingsWithExistingTheme() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = LearningDatabase.get(context)
        val repo = MemoryReviewRepository(db)
        repo.propose(AgentMemoryScope.GLOBAL, "core", "回答语言与表达", "默认用中文，先给结论，然后解释必要细节。", "默认用中文，先给结论", "conversation:example")
        panel("memory-library") { AgentMemoryScreen(AgentMemoryUiState(isLoading = false), {}) }.use { p ->
            p.await { p.texts().contains("回答语言与表达") }
            p.capture("memory-library")
            p.click("策略")
            p.await { p.texts().contains("空闲时自动整理") }
            p.capture("memory-settings")
        }
    }

    @Test @Config(qualifiers = "w320dp-h720dp-xhdpi")
    fun selfLearningUsesReadableLabelsOnNarrowDarkScreen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        LearningRepository.get(context).propose(LearningProposalInput(LearningKind.PROCEDURE,
            "安装后核对无障碍状态", "安装应用后检查服务是否保持启用，再验证工具调用。", "安装后核对服务", "run:example"))
        panel("learning", dark = true) { LearningSettingsScreen(onBack = {}) }.use { p ->
            p.await { p.texts().contains("安装后核对无障碍状态") }
            assertFalse(p.texts().any { it.contains("pending ·") })
            p.capture("learning-narrow-dark")
            p.click("策略")
            p.await { p.texts().contains("启用自学习") }
            p.capture("learning-settings-narrow-dark")
        }
    }

    @Test fun memorySecondaryTabsReturnToTheStableModuleTabRow() = runBlocking {
        panel("memory-navigation") { AgentMemoryScreen(AgentMemoryUiState(isLoading = false), {}) }.use { panel ->
            panel.await { panel.texts().contains("文档") }
            panel.click("文档")
            panel.await { panel.texts().contains("记忆范围") }
            assertTrue(panel.texts().containsAll(listOf("记忆库", "文档", "自学习", "策略")))
            panel.capture("memory-document-with-module-tabs")
            panel.click("记忆库")
            panel.await { panel.texts().contains("记忆概况") }

            panel.click("自学习")
            panel.await { panel.texts().contains("候选") }
            assertTrue(panel.texts().containsAll(listOf("记忆库", "文档", "自学习", "策略")))
            panel.capture("memory-learning-with-module-tabs")
            panel.back()
            panel.await { panel.texts().containsAll(listOf("记忆库", "文档", "自学习", "策略")) }

            // Re-entering a secondary module must not leave the previous BackHandler active.
            // This catches callback overlap where one stale handler consumes every later back press.
            panel.click("文档")
            panel.await { panel.texts().contains("记忆范围") }
            panel.back()
            panel.await { panel.texts().containsAll(listOf("记忆库", "文档", "自学习", "策略")) }
        }
    }

    @Test @Config(qualifiers = "w320dp-h720dp-xhdpi")
    fun memoryConflictCanBeReviewedWithLargeTextOnNarrowScreen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = LearningDatabase.get(context)
        val repo = MemoryReviewRepository(db)
        val old = requireNotNull(repo.propose(AgentMemoryScope.GLOBAL, "core", "回复语言",
            "以后请使用中文回答", "以后请使用中文回答", "conversation:first"))
        repo.decide(old.id, old.updatedAt, "confirm")
        repo.propose(AgentMemoryScope.GLOBAL, "core", "回复语言", "以后请使用英文回答", "以后请使用英文回答", "conversation:second")
        panel("memory-large", fontScale = 1.3f) { AgentMemoryScreen(AgentMemoryUiState(isLoading = false), {}) }.use { p ->
            p.await { p.texts().contains("回复语言") }
            p.capture("memory-narrow-large-font")
            p.click("回复语言")
            p.await { p.texts().contains("已有内容") }
            assertTrue(p.texts().contains("以后请使用中文回答"))
            assertTrue(p.texts().contains("以后请使用英文回答"))
            p.capture("memory-conflict-narrow-large-font")
        }
    }

    private fun panel(name: String, dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit): Panel = Panel(dark, fontScale, content)
    internal class Panel(dark: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) : AutoCloseable {
        private val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        private val activity = activityController.get()
        private val clock = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        private val recomposer = Recomposer(scope.coroutineContext)
        init {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val view = ComposeView(activity)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = if (dark) AppearanceThemeMode.DARK else AppearanceThemeMode.LIGHT, blurEnabled = false), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalSquircleEnabled provides false,
                        LocalDensity provides Density(LocalDensity.current.density, fontScale)) { content() }
                }
            }
            activity.setContentView(view)
            advance()
        }
        private fun advance(frames: Int = 30) { repeat(frames) {
            Snapshot.sendApplyNotifications()
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            clock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
            roots().forEach { it.measureAndLayoutForTest() }
        } }
        fun await(predicate: () -> Boolean) {
            repeat(240) { if (predicate()) return; advance(1); Thread.sleep(2) }
            assertTrue("Missing UI content: ${texts()}", predicate())
        }
        private fun root(): ViewRootForTest = findRoot(activity.window.decorView) ?: error("Missing Compose root")
        private fun roots(): List<ViewRootForTest> {
            val global = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"), "getInstance")
            val views = ReflectionHelpers.getField<List<View>>(global, "mViews")
            return (listOf(root()) + views.mapNotNull(::findRoot)).distinct()
        }
        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
            return null
        }
        private fun nodes(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap { nodes(it) }
        private fun allNodes() = roots().flatMap { nodes(it.semanticsOwner.unmergedRootSemanticsNode) }
        fun texts(): List<String> = allNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
        fun click(text: String) {
            var node: SemanticsNode? = allNodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { item -> item.text == text } == true }
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            assertTrue(node?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
            advance()
        }
        fun clickDescription(description: String) {
            var node: SemanticsNode? = allNodes().first {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { item -> item == description } == true
            }
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            assertTrue(node?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
            advance()
        }
        fun back() {
            activity.onBackPressedDispatcher.onBackPressed()
            advance()
        }
        fun scrollUntil(text: String) {
            repeat(30) {
                if (texts().any { it == text }) return
                val action = allNodes().firstNotNullOfOrNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }
                    ?: error("Missing scroll container")
                action(0f, 500f)
                advance()
            }
            assertTrue("Missing scroll target: $text", texts().contains(text))
        }
        fun capture(name: String) {
            val view = roots().last().view
            assertTrue(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/memory-learning-ui/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        override fun close() { recomposer.cancel(); scope.cancel(); activityController.pause().stop().destroy() }
    }
}
