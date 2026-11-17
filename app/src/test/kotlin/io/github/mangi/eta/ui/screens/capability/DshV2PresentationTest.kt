package io.github.mangi.eta.ui.screens.capability

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.screens.memory.MemoryLearningPresentationTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w320dp-h960dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DshV2PresentationTest {
    @Test fun narrowDarkPanelRendersTheRealV2FormAndSubmitsTypedInputThroughItsQueue() {
        val field = JSONObject().put("type", "textField").put("label", "报告名称").put("bind", "data.form.name").put("required", true)
        val toggle = JSONObject().put("type", "toggle").put("label", "保留来源").put("bind", "data.form.sources")
        val view = JSONObject().put("v", 2).put("type", "form").put("submit", "save").put("submitLabel", "保存")
            .put("style", JSONObject().put("card", "tinted")).put("children", JSONArray().put(field).put(toggle))
        val data = JSONObject().put("form", JSONObject().put("name", "每日报告").put("sources", true))
        val calls = mutableListOf<DshViewAction>()
        MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.3f) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                DshViewRenderer(view, data, null, onAction = { calls += it; true }, imageScope = "presentation", connected = true, generation = 1, image = { error("No image expected") })
            }
        }.use { panel ->
            panel.await { panel.texts().containsAll(listOf("插件内容", "报告名称", "保留来源", "保存")) }
            panel.capture("dsh-v2-form-narrow-dark")
            panel.click("保存")
            panel.await { calls.size == 1 }
            assertEquals("save", calls.single().id)
            assertEquals("每日报告", calls.single().value.getJSONObject("values").getString("data.form.name"))
            assertTrue(calls.single().value.getJSONObject("values").getBoolean("data.form.sources"))
            assertTrue(calls.single().userGesture)
        }
    }
}
