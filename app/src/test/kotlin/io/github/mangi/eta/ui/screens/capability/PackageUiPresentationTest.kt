package io.github.mangi.eta.ui.screens.capability

import android.app.Application
import io.github.mangi.eta.agent.packages.PackageUiService
import io.github.mangi.eta.agent.packages.PackageUiServiceTest
import io.github.mangi.eta.ui.screens.memory.MemoryLearningPresentationTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w320dp-h840dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackageUiPresentationTest {
    @Test fun asynchronousActionShowsLoadingWhileItRunsAndCommitsOnlyTheFinalState() {
        val fixture = PackageUiServiceTest(); val module = fixture.install()
        val finish = java.util.concurrent.CountDownLatch(1)
        val service = PackageUiService(RuntimeEnvironment.getApplication(), streamExecute = { _, args, _, _, event ->
            if (args.has("actionId")) {
                val loading = fixture.document(1)
                loading.getJSONObject("tree").getJSONArray("children").getJSONObject(0).getJSONObject("props").put("text", "正在生成")
                event(io.github.mangi.eta.agent.runtime.AgentEvent.PluginUiFrame(module.record.id, module.record.digest, module.id, 1, loading.toString()))
                check(finish.await(10, java.util.concurrent.TimeUnit.SECONDS))
            }
            JSONObject().put("ok", true).put("value", fixture.document(if (args.has("actionId")) 2 else 0)).toString()
        })
        MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.3f) { PackageUiScreen(module, onBack = {}, service = service) }.use { panel ->
            try {
                panel.await { panel.texts().contains("次数 0") }; panel.click("增加")
                panel.await { panel.texts().contains("正在生成") }
                panel.capture("package-compose-live-loading")
                finish.countDown()
                panel.await { panel.texts().contains("次数 2") }
                assertFalse(panel.texts().contains("正在生成"))
            } finally { finish.countDown() }
        }
    }
    @Test fun installedModuleRendersInOriginalScaffoldAndActualButtonUpdatesItsState() {
        val fixture = PackageUiServiceTest(); val module = fixture.install()
        var actions = 0; var closed = false
        val service = PackageUiService(RuntimeEnvironment.getApplication()) { _, args, _, _ ->
            if (args.has("actionId")) { assertEquals("__action_1", args.getString("actionId")); actions++ }
            JSONObject().put("ok", true).put("value", fixture.document(actions)).toString()
        }
        MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.3f) {
            PackageUiScreen(module, onBack = { closed = true }, service = service)
        }.use { panel ->
            panel.await { panel.texts().contains("次数 0") }
            panel.click("增加")
            panel.await { panel.texts().contains("次数 1") }
            assertEquals(1, actions)
            panel.capture("package-compose-screen-narrow-dark")
            assertFalse(closed)
            panel.back()
            assertTrue(closed)
        }
    }

    @Test fun declaredRouteNavigationAndBackRestoreThePriorScreenState() {
        val fixture = PackageUiServiceTest(); val module = fixture.install(secondRoute = true)
        var closed = false
        val service = PackageUiService(RuntimeEnvironment.getApplication()) { target, args, _, _ ->
            val value = fixture.document(if (target.id == "details") 9 else 0)
            if (args.has("actionId")) value.getJSONArray("effects").put(JSONObject().put("type", "navigate").put("route", "details"))
            JSONObject().put("ok", true).put("value", value).toString()
        }
        MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.3f) {
            PackageUiScreen(module, onBack = { closed = true }, service = service)
        }.use { panel ->
            panel.await { panel.texts().contains("次数 0") }
            panel.click("增加")
            panel.await { panel.texts().contains("次数 9") }
            panel.back()
            panel.await { panel.texts().contains("次数 0") }
            assertFalse(closed)
            panel.back(); assertTrue(closed)
        }
    }

    @Test fun debouncedFieldCommitsItsLatestTextBeforeTheNextCommandAndFailureDoesNotAutoRepeat() {
        val fixture = PackageUiServiceTest(); val module = fixture.install()
        var fieldValue = "old"; var calls = 0; var fail = false
        val service = PackageUiService(RuntimeEnvironment.getApplication()) { _, args, _, _ ->
            if (args.has("actionId")) { calls++; if (fail) error("controlled field error"); fieldValue = args.getString("payload") }
            val value = fixture.document()
            val field = JSONObject().put("__composeNode", true).put("type", "TextField").put("props", JSONObject().put("value", fieldValue)
                .put("label", "名称").put("onValueChange", JSONObject().put("__actionId", "__action_2"))).put("children", org.json.JSONArray()).put("slots", JSONObject())
            value.getJSONObject("tree").getJSONArray("children").put(field)
            JSONObject().put("ok", true).put("value", value).toString()
        }
        MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.3f) {
            PackageUiScreen(module, onBack = {}, service = service)
        }.use { panel ->
            panel.await { panel.texts().contains("名称") }
            panel.editFirstTextField("latest input")
            panel.await { calls == 1 && fieldValue == "latest input" }
            fail = true
            panel.editFirstTextField("keep this draft")
            panel.await { calls == 2 && panel.texts().any { it.contains("controlled field error") } }
            panel.capture("package-compose-input-failure-draft")
            panel.await { panel.texts().contains("重新读取") }
            assertEquals(2, calls)
            assertEquals("latest input", fieldValue)
        }
    }
}
