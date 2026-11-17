package io.github.mangi.eta.ui.screens.capability

import android.app.Application
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
class DshChartPresentationTest {
    @Test fun donutLegendSelectsRealCategoryAndPointControlsMoveWithoutOpeningAnotherWindow() {
        val view = JSONObject().put("kind", "donut").put("unit", "元").put("caption", "本周花费")
            .put("categories", JSONArray().put("模型").put("工具").put("其他"))
            .put("series", JSONArray().put(JSONObject().put("values", JSONArray().put(30).put(20).put(50))))
        MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.3f) { DshChartView(view, null) }.use { panel ->
            panel.await { panel.texts().contains("工具") }
            panel.click("工具")
            panel.await { panel.texts().any { it.contains("工具 · 20元 · 20%") } }
            panel.clickDescription("下一项")
            panel.await { panel.texts().any { it.contains("其他 · 50元 · 50%") } }
            panel.capture("dsh-donut-detail-narrow-dark")
        }
    }
    @Test fun lineLegendCanHideSeriesAndStylesReportOpensInlineWithPreciseFieldPaths() {
        val chart = JSONObject().put("type", "chart").put("categories", JSONArray().put("今天").put("昨天"))
            .put("series", JSONArray().put(JSONObject().put("name", "请求").put("values", JSONArray().put(3).put(1)))
                .put(JSONObject().put("name", "缓存").put("values", JSONArray().put(1).put(2))))
        val view = JSONObject().put("v", 2).put("type", "stack").put("style", JSONObject().put("opacity", .4))
            .put("children", JSONArray().put(chart))
        MemoryLearningPresentationTest.Panel(dark = false, fontScale = 1.3f) {
            DshViewRenderer(view, null, null, onAction = { false }, imageScope = "chart", connected = true, generation = 1, image = { byteArrayOf() })
        }.use { panel ->
            panel.await { panel.texts().contains("缓存") }; panel.click("缓存")
            panel.await { panel.texts().contains("缓存（已隐藏）") }
            panel.clickDescription("样式诊断 1 项")
            panel.await { panel.texts().contains("root.style.opacity") }
            panel.capture("dsh-chart-diagnostics-narrow-light")
        }
    }
}
