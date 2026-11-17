package io.github.mangi.eta.ui.screens.capability

import android.app.Application
import androidx.compose.runtime.*
import io.github.mangi.eta.agent.dsh.*
import io.github.mangi.eta.ui.screens.memory.MemoryLearningPresentationTest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DshPanelSubscriptionTest {
    @Test fun reloadingTheSamePanelAfterItsFeedEndsCreatesANewSubscriptionAndRetiresTheOldOne() {
        DshHostClient("http://127.0.0.1:3080/", DshCredentials()).use { host ->
            lateinit var socket: NativeSlotsSocket
            DshStreamClient(host) { request, listener -> NativeSlotsSocket(request, listener).also { socket = it; it.open() } }.use { stream ->
                runBlocking { stream.connect() }
                val view = DshView("native", "overview", "Overview", JSONObject().put("type", "text").put("text", "Panel"), JSONObject(), "initial", true)
                var generation by mutableLongStateOf(1)
                var ended = 0
                var latest = ""
                MemoryLearningPresentationTest.Panel {
                    DshPanelSubscription(stream, view, JSONObject().put("sessionId", "remote-session"), generation,
                        onData = { latest = (it.data as? JSONObject)?.optString("text").orEmpty() }, onEnded = { ended++ })
                }.use { panel ->
                    panel.await { socket.sent.count { it.optString("t") == "sub" } == 1 }
                    val old = socket.sent.last { it.optString("t") == "sub" }.getString("sub")
                    socket.emit(JSONObject().put("t", "end").put("sub", old).put("reason", "not-found"))
                    panel.await { ended == 1 }
                    generation++
                    panel.await { socket.sent.count { it.optString("t") == "sub" } == 2 }
                    val replacement = socket.sent.last { it.optString("t") == "sub" }.getString("sub")
                    assertNotEquals(old, replacement)
                    assertTrue(socket.sent.any { it.optString("t") == "unsub" && it.optString("sub") == old })
                    socket.emit(JSONObject().put("t", "data").put("sub", replacement).put("rev", "new")
                        .put("data", JSONObject().put("text", "reloaded")))
                    panel.await { latest == "reloaded" }
                    assertEquals(1, ended)
                }
            }
        }
    }
}
