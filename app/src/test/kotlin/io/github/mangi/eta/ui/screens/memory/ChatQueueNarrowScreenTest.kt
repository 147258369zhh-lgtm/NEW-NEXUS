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
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.AgentChatQueueStrip
import io.github.mangi.eta.ui.components.AgentChatQueueStrip
import io.github.mangi.eta.ui.model.QueuedMessageUi
import java.io.File
import java.time.Duration
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w320dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatQueueNarrowScreenTest {
    @Test fun longChineseQueueActionsRemainAccessibleAndVisibleAtLargeFontScale() {
        val calls = mutableListOf<String>()
        Panel(fontScale = 1.4f) {
            AgentChatQueueStrip(
                queuedMessages = listOf(
                    QueuedMessageUi("first", "第一条很长的中文排队消息内容，需要在窄屏和大字体下完整保留语义并允许用户撤回继续编辑"),
                    QueuedMessageUi("second", "第二条排队消息"),
                ),
                isStreaming = true,
                onSteer = { calls += "steer:$it" },
                onRecall = { calls += "recall:$it" },
                onRemove = { calls += "remove:$it" },
            )
        }.use { panel ->
            panel.await { panel.descriptions().containsAll(listOf("立即插话发送", "移除排队消息")) }
            panel.clickDescription("移除排队消息")
            assertEquals(listOf("remove:first"), calls)
            panel.clickText("第一条很长的中文排队消息内容，需要在窄屏和大字体下完整保留语义并允许用户撤回继续编辑")
            assertEquals(listOf("remove:first", "recall:first"), calls)
            val actions = panel.nodesWithDescription("立即插话发送") + panel.nodesWithDescription("移除排队消息")
            assertTrue(actions.all { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f })
            panel.capture("chat-queue-long-chinese-narrow")
        }
    }

    private class Panel(fontScale: Float, content: @Composable () -> Unit) : AutoCloseable {
        private val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        private val activity = activityController.get()
        private val frameClock = BroadcastFrameClock()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + frameClock)
        private val recomposer = Recomposer(scope.coroutineContext)
        init {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            val view = ComposeView(activity)
            view.setParentCompositionContext(recomposer)
            view.setContent {
                AgentAppTheme(AppearanceSettings(themeMode = AppearanceThemeMode.LIGHT, blurEnabled = false), applyInterfaceScale = false) {
                    CompositionLocalProvider(LocalSquircleEnabled provides false,
                        LocalDensity provides Density(LocalDensity.current.density, fontScale)) { content() }
                }
            }
            activity.setContentView(view)
            advance()
        }
        private fun advance() { repeat(30) {
            Snapshot.sendApplyNotifications()
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
            frameClock.sendFrame(SystemClock.uptimeMillis() * 1_000_000L)
            roots().forEach { it.measureAndLayoutForTest() }
        } }
        fun await(predicate: () -> Boolean) {
            repeat(180) { if (predicate()) return; advance(); Thread.sleep(1) }
            assertTrue("Missing queue UI: ${descriptions()}", predicate())
        }
        private fun root(): ViewRootForTest = findRoot(activity.window.decorView) ?: error("Missing Compose root")
        private fun roots(): List<ViewRootForTest> {
            val global = ReflectionHelpers.callStaticMethod<Any>(Class.forName("android.view.WindowManagerGlobal"), "getInstance")
            val views = ReflectionHelpers.getField<List<View>>(global, "mViews")
            return (listOf(root()) + views.mapNotNull(::findRoot)).distinct()
        }
        private fun findRoot(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) findRoot(view.getChildAt(index))?.let { return it }
            return null
        }
        private fun allNodes(node: SemanticsNode = root().semanticsOwner.unmergedRootSemanticsNode): List<SemanticsNode> =
            listOf(node) + node.children.flatMap(::allNodes)
        fun descriptions() = allNodes().flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
        fun nodesWithDescription(value: String) = allNodes().filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(value) == true }
        fun clickDescription(value: String) = click(nodesWithDescription(value).first())
        fun clickText(value: String) = click(allNodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true })
        private fun click(target: SemanticsNode) {
            var node: SemanticsNode? = target
            while (node != null && node.config.getOrNull(SemanticsActions.OnClick) == null) node = node.parent
            assertTrue(node?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true)
            advance()
        }
        fun capture(name: String) {
            val view = root().view
            assertTrue(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/d01-ui/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        override fun close() { recomposer.cancel(); scope.cancel(); activityController.destroy() }
    }
}
