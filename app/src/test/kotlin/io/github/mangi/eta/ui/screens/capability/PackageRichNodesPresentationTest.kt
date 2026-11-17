package io.github.mangi.eta.ui.screens.capability

import android.app.Application
import androidx.compose.runtime.*
import io.github.mangi.eta.agent.packages.PackageUiService
import io.github.mangi.eta.agent.packages.PackageUiServiceTest
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
@Config(sdk = [36], application = Application::class, qualifiers = "w320dp-h840dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackageRichNodesPresentationTest {
    private fun node(type: String, props: JSONObject = JSONObject(), children: List<JSONObject> = emptyList()) = JSONObject()
        .put("__composeNode", true).put("type", type).put("props", props).put("children", JSONArray(children)).put("slots", JSONObject())
    private fun callback(id: Int) = JSONObject().put("__actionId", "__action_$id")
    @Test fun installedPackageImageLoadsItsVerifiedAssetAndDrawsInTheOriginalScreen() {
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(32, 150, 101))
        val output = java.io.ByteArrayOutputStream(); bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        val module = PackageUiServiceTest().install(assets = mapOf("images/result.png" to output.toByteArray()))
        val image = node("Image", JSONObject().put("src", "images/result.png").put("contentDescription", "处理结果图").put("aspectRatio", 2))
        val document = JSONObject().put("tree", node("Column", children = listOf(node("Text", JSONObject().put("text", "结果")), image)))
            .put("state", JSONObject()).put("memo", JSONObject()).put("effects", JSONArray())
        val service = PackageUiService(org.robolectric.RuntimeEnvironment.getApplication()) { _, _, _, _ ->
            JSONObject().put("ok", true).put("value", document).toString()
        }
        MemoryLearningPresentationTest.Panel(dark = true) { PackageUiScreen(module, onBack = {}, service = service) }.use { panel ->
            panel.await { panel.texts().contains("结果") && !panel.texts().contains("处理结果图") }
            panel.capture("package-verified-asset-image")
        }
    }
    @Test fun modifierClickOpensInlineOptionsAndChoiceClosesThemWithoutASecondWindow() {
        var selected = "默认"
        MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.3f) {
            var expanded by remember { mutableStateOf(false) }
            var label by remember { mutableStateOf("默认") }
            val text = node("Text", JSONObject().put("text", "音色：$label").put("modifier", JSONObject().put("__modifierOps", JSONArray()
                .put(JSONObject().put("name", "clickable").put("args", JSONArray().put(callback(1)))))))
            val menu = node("DropdownMenu", JSONObject().put("expanded", expanded).put("onDismissRequest", callback(3)),
                listOf(node("DropdownMenuItem", JSONObject().put("text", "清晰").put("onClick", callback(2)))))
            PackageComposeNode(node("Column", children = listOf(text, menu)), enabled = true, action = { id, _ ->
                when (id) { "__action_1" -> expanded = true; "__action_2" -> { selected = "清晰"; label = selected; expanded = false }; "__action_3" -> expanded = false }
            }, close = {})
        }.use { panel ->
            panel.await { panel.texts().contains("音色：默认") }; panel.click("音色：默认")
            panel.await { panel.texts().contains("清晰") }; panel.click("清晰")
            panel.await { panel.texts().contains("音色：清晰") && !panel.texts().contains("清晰") }
            assertEquals("清晰", selected)
        }
    }
    @Test fun boundedCanvasDrawsShapesAndTextAndReportsItsRealSize() {
        var sizeCalls = 0
        var active by mutableStateOf(true)
        val commands = JSONArray().put(JSONObject().put("type", "roundRect").put("x", 8).put("y", 8).put("width", 180).put("height", 64).put("radius", 6).put("unit", "dp").put("color", "#3265CA"))
            .put(JSONObject().put("type", "drawText").put("text", "任务已完成").put("x", 20).put("y", 28).put("fontSize", 14).put("unit", "dp").put("color", "#FFFFFF"))
            .put(JSONObject().put("type", "circle").put("cx", 220).put("cy", 40).put("radius", 12).put("unit", "dp").put("color", "#209665"))
        val canvas = node("Canvas", JSONObject().put("height", 140).put("commands", commands).put("onSizeChanged", callback(1)))
        MemoryLearningPresentationTest.Panel(dark = false) {
            PackageComposeNode(canvas, enabled = active, action = { id, payload ->
                assertEquals("__action_1", id); assertTrue((payload as JSONObject).getInt("width") > 0); sizeCalls++
            }, close = {})
        }.use { panel ->
            panel.await { sizeCalls == 1 }; active = false; panel.await { !active }; active = true
            panel.capture("package-canvas-shapes-text")
            assertEquals(1, sizeCalls)
        }
    }
}
