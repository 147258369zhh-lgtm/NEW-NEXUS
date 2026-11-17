package io.github.mangi.eta.ui.screens.capability

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import io.github.mangi.eta.agent.dsh.*
import io.github.mangi.eta.ui.screens.memory.MemoryLearningPresentationTest
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Text

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w320dp-h840dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DshMediaPresentationTest {
    @Test fun appOwnedMediaControlsAndCaptionCanBeClosedFromTheActualForegroundUi() = runBlocking {
        MediaFixture().use { fixture -> fixture.session().use { session ->
            val connection = launch { session.maintainConnection() }; val backend = TestMediaBackend(); val controller = fixture.controller(this, backend)
            try {
                val plugin = fixture.ready(session); val effects = session.invokeFromTap(plugin, plugin.contributions.single { it.id == "speak" })
                MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.3f) {
                    Box(Modifier.fillMaxSize()) { Text("会话内容"); DshMediaSurface("chat", controller) }
                }.use { panel ->
                    controller.apply("chat", null, session, plugin, JSONObject(), effects.values[2], effects) { false }
                    yield()
                    panel.await { panel.texts().containsAll(listOf("朗读插件", "字幕更新")) }
                    panel.capture("dsh-media-caption-narrow-dark")
                    panel.clickDescription("关闭插件字幕")
                    panel.await { controller.state.value.surface.isEmpty() }
                    assertEquals("overlay.closed", fixture.socket.events().single().getString("event"))
                }
            } finally { controller.stop(); connection.cancelAndJoin() }
        } }
    }
}
