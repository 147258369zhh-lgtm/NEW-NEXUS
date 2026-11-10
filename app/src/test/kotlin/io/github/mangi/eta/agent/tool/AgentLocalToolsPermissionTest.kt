package io.github.mangi.eta.agent.tool

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import io.github.mangi.eta.agent.device.RootShellDeviceController
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentScreenObservationContract
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.GuiControlCoordinator
import io.github.mangi.eta.core.AgentLogger
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLocalToolsPermissionTest {
    @Test
    fun releaseAndUserTakeoverInvalidateBothNodesAndRawScreenCoordinates() {
        val coordinator = GuiControlCoordinator()
        val control = coordinator.openRun("gui-test", AgentRunController())
        val tools = tools(
            guiControl = control,
            screenObservationProvider = { observation("observed", it.includeScreenshot) },
            beforeToolExecution = { name ->
                if (name in setOf("observe_screen", "release_gui")) ToolExecutionDecision.Allow
                else ToolExecutionDecision.Reject("ACTION_REACHED", "动作执行前测试边界")
            },
        )
        fun call(name: String, arguments: String = "{}") = JSONObject(
            tools.execute(AgentModelClient.ToolCall(name, name, arguments)).content,
        )
        tools.use {
            assertTrue(call("observe_screen").getBoolean("ok"))
            assertEquals("ACTION_REACHED", call("tap", """{"x":1,"y":1,"coordinate_space":"screen"}""").getString("code"))
            assertTrue(call("release_gui").getBoolean("ok"))
            assertEquals("NO_OBSERVATION", call("tap", """{"x":1,"y":1,"coordinate_space":"screen"}""").getString("code"))
            assertEquals("NO_OBSERVATION", call("tap_element", """{"index":0,"observation_id":"observed"}""").getString("code"))
            assertTrue(call("observe_screen").getBoolean("ok"))
            assertTrue(coordinator.pauseForUser("gui-test"))
            assertTrue(coordinator.resumeForUser())
            assertEquals("NO_OBSERVATION", call("type_text", """{"text":"旧页面不能输入"}""").getString("code"))
            assertTrue(call("observe_screen").getBoolean("ok"))
            assertEquals("ACTION_REACHED", call("type_text", """{"text":"新观察后到达动作边界"}""").getString("code"))
        }
    }

    @Test
    fun backgroundToolsDoNotAcquireAnotherRunsGuiControl() {
        val coordinator = GuiControlCoordinator()
        val owner = coordinator.openRun("owner", AgentRunController())
        owner.withAction { }
        val background = coordinator.openRun("background", AgentRunController())
        try {
            tools(guiControl = background).use { tools ->
                val result = tools.execute(AgentModelClient.ToolCall("terminal", "terminal", "{}"))
                assertEquals("TERMINAL_TOOLS_DISABLED", JSONObject(result.content).getString("code"))
                assertEquals("owner", coordinator.state.value.ownerRunId)
            }
        } finally {
            owner.close()
        }
    }

    @Test
    fun revokedRootRejectsOldPrivilegedCallsBeforeAnyOperation() {
        val root = AtomicBoolean(true)
        val tools = tools(
            rootAvailable = root::get,
            terminalEnabled = { true },
        )
        root.set(false)
        listOf(
            "search_bills" to "{}",
            "set_setting" to "{}",
            "terminal" to "{\"action\":\"open\",\"identity\":\"root\"}",
            "edit_file" to "{\"path\":\"x\",\"identity\":\"root\",\"old_text\":\"a\",\"new_text\":\"b\"}",
            "press_key" to "{\"button\":\"PASTE\"}",
        ).forEach { (name, args) ->
            val result = tools.execute(AgentModelClient.ToolCall("old-$name", name, args))
            assertEquals("ROOT_REQUIRED", JSONObject(result.content).getString("code"))
        }
        tools.close()
    }

    @Test
    fun personalContextRechecksSensitiveReadPermissionAndRedactsDeniedCalls() {
        val enabled = AtomicBoolean(true)
        val tools = tools(rootAvailable = { true }, sensitiveReadEnabled = enabled::get,
            beforeToolExecution = { error("已关闭个人信息读取时不能进入数据源执行阶段") })
        tools.use {
            enabled.set(false)
            val result = it.execute(AgentModelClient.ToolCall("personal", "search_bills", "{}"))
            assertEquals("DEVICE_SENSITIVE_READ_TOOLS_DISABLED", JSONObject(result.content).getString("code"))
            assertTrue(result.sensitive)
        }
    }

    @Test
    fun terminalPermissionIsRecheckedImmediatelyBeforeExecution() {
        val enabled = AtomicBoolean(true)
        val tools = tools(terminalEnabled = enabled::get)
        enabled.set(false)

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "terminal",
                argumentsJson = "{\"command\":\"id\"}",
            )
        )

        assertEquals("TERMINAL_TOOLS_DISABLED", JSONObject(result.content).getString("code"))
        listOf("terminal_job", "terminal_daemon", "read_file", "edit_file", "glob_files", "grep_files", "stat_file").forEach { name ->
            val fileResult = tools.execute(AgentModelClient.ToolCall("disabled-$name", name, "{}"))
            assertEquals(name, "TERMINAL_TOOLS_DISABLED", JSONObject(fileResult.content).getString("code"))
        }
        tools.close()
    }

    @Test
    fun browserPermissionIsRecheckedImmediatelyBeforeExecution() {
        val enabled = AtomicBoolean(true)
        val tools = tools(browserEnabled = enabled::get)
        enabled.set(false)

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "browser_use",
                argumentsJson = "{\"action\":\"get_page_info\"}",
            )
        )

        assertEquals("BROWSER_TOOLS_DISABLED", JSONObject(result.content).getString("code"))
        listOf("web_search", "fetch_url").forEach { name ->
            val webResult = tools.execute(AgentModelClient.ToolCall("disabled-$name", name, "{}"))
            assertEquals(name, "BROWSER_TOOLS_DISABLED", JSONObject(webResult.content).getString("code"))
        }
        tools.close()
    }

    @Test
    fun memoryPermissionIsRecheckedImmediatelyBeforeExecution() {
        val enabled = AtomicBoolean(true)
        val tools = tools(memoryEnabled = enabled::get)
        enabled.set(false)

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-memory",
                name = "memory_get",
                argumentsJson = "{}",
            ),
        )

        assertEquals("MEMORY_DISABLED", JSONObject(result.content).getString("code"))
        assertEquals(true, result.sensitive)
        tools.close()
    }

    @Test
    fun roleplayCannotWriteRealMemoryEvenWithAStaleToolDeclaration() {
        val tools = tools(memoryEnabled = { true }, memoryWritable = false)
        try {
            val result = tools.execute(AgentModelClient.ToolCall("write", "memory_write", "{}"))
            assertEquals("REAL_MEMORY_READ_ONLY", JSONObject(result.content).getString("code"))
            assertTrue(result.sensitive)
        } finally {
            tools.close()
        }
    }

    @Test
    fun foregroundToolIsRejectedWhenEntrySurfaceIsNotReady() {
        val tools = tools(
            beforeToolExecution = {
                ToolExecutionDecision.Reject(
                    code = "ENTRY_SURFACE_NOT_READY",
                    message = "入口窗口尚未确认关闭",
                )
            },
        )

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "tap",
                argumentsJson = "{\"x\":100,\"y\":200,\"coordinate_space\":\"screen\"}",
            ),
        )

        assertEquals("ENTRY_SURFACE_NOT_READY", JSONObject(result.content).getString("code"))
        tools.close()
    }

    @Test
    fun foregroundToolPropagatesAccessibilityGateFailure() {
        for (code in listOf("ACCESSIBILITY_PROTECTION_UNAVAILABLE", "ACCESSIBILITY_DISCONNECTED")) {
            val tools = tools(
                beforeToolExecution = {
                    ToolExecutionDecision.Reject(
                        code = code,
                        message = "无障碍服务未连接",
                    )
                },
            )

            try {
                val result = tools.execute(
                    AgentModelClient.ToolCall(
                        id = "call-1",
                        name = "scroll",
                        argumentsJson = "{\"direction\":\"down\"}",
                    ),
                )
                val json = JSONObject(result.content)

                assertEquals(code, json.getString("code"))
                assertEquals("无障碍服务未连接", json.getString("message"))
            } finally {
                tools.close()
            }
        }
    }

    @Test
    fun screenshotCoordinatesRequireACurrentScreenshotCoordinateSpace() {
        val tools = tools()

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "tap",
                argumentsJson = "{\"x\":100,\"y\":200,\"coordinate_space\":\"screenshot\"}",
            ),
        )

        assertEquals("INVALID_ARGUMENT", JSONObject(result.content).getString("code"))
        tools.close()
    }

    @Test
    fun missingActionArgumentsDoNotBecomeZeroCoordinatesOrEmptyText() {
        val tools = tools()
        try {
            listOf(
                "tap" to """{"coordinate_space":"screen","y":10}""",
                "swipe" to """{"coordinate_space":"screen","y1":10,"x2":20,"y2":20}""",
                "type_text" to "{}",
                "set_clipboard" to "{}",
            ).forEach { (name, arguments) ->
                val result = tools.execute(AgentModelClient.ToolCall("missing", name, arguments))
                assertEquals(name, "INVALID_ARGUMENT", JSONObject(result.content).getString("code"))
            }
        } finally { tools.close() }
    }

    @Test
    fun missingAndNonTextInputDoNotChangeClipboardOrReachDeviceActions() {
        val context = RuntimeEnvironment.getApplication() as Context
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "existing text"))
        tools().use { tools ->
            listOf("type_text", "input_text", "replace_text", "set_clipboard", "paste_text", "wait_for_text").forEach { name ->
                listOf("{}", """{"text":null}""", """{"text":{"value":"replacement"}}""").forEach { args ->
                    val result = JSONObject(tools.execute(AgentModelClient.ToolCall("invalid-$name", name, args)).content)
                    assertEquals(result.toString(), "INVALID_ARGUMENT", result.getString("code"))
                    assertEquals("existing text", clipboard.primaryClip!!.getItemAt(0).text.toString())
                }
            }
            val cleared = JSONObject(tools.execute(AgentModelClient.ToolCall("clear", "set_clipboard", """{"text":""}""")).content)
            assertTrue(cleared.toString(), cleared.getBoolean("ok"))
            assertEquals("", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test
    fun coordinateToolsRequireExplicitCoordinateSpace() {
        val tools = tools()
        listOf(
            "{\"x\":100,\"y\":200}",
            "{\"x\":100,\"y\":200,\"coordinate_space\":\"pixel\"}",
            "{\"x\":1000,\"y\":200,\"coordinate_space\":\"normalized\"}",
        ).forEach { arguments ->
            val result = tools.execute(AgentModelClient.ToolCall(id = "call-1", name = "tap", argumentsJson = arguments))
            assertEquals(arguments, "INVALID_ARGUMENT", JSONObject(result.content).getString("code"))
        }
        tools.close()
    }

    @Test
    fun typeTextRejectsInvalidModesBeforeTouchingTheScreen() {
        val tools = tools()
        listOf(
            "{\"text\":\"hi\",\"mode\":\"paste\"}",
            "{\"text\":\"\",\"mode\":\"append\"}",
            "{\"text\":\"hi\",\"mode\":\"append\",\"index\":1}",
        ).forEach { arguments ->
            val result = tools.execute(AgentModelClient.ToolCall(id = "call-1", name = "type_text", argumentsJson = arguments))
            assertEquals(arguments, "INVALID_ARGUMENT", JSONObject(result.content).getString("code"))
        }
        // 无障碍未连接时 replace 明确失败，不会退回到盲发按键。
        val replace = tools.execute(AgentModelClient.ToolCall(id = "call-2", name = "type_text", argumentsJson = "{\"text\":\"hi\"}"))
        assertEquals("ACCESSIBILITY_UNAVAILABLE", JSONObject(replace.content).getString("code"))
        tools.close()
    }

    @Test
    fun textInputWithoutAccessibilityDoesNotSendBlindShellKeys() {
        val tools = tools()

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "call-1",
                name = "input_text",
                argumentsJson = "{\"text\":\"hello\"}",
            ),
        )

        assertEquals("ACCESSIBILITY_UNAVAILABLE", JSONObject(result.content).getString("code"))
        tools.close()
    }

    @Test
    fun defaultScreenObservationReturnsTreeWithoutImage() {
        var received: AgentScreenObservationContract.Options? = null
        val tools = tools(
            screenObservationProvider = { options ->
                received = options
                observation(
                    observationId = "o-tree",
                    screenshotRequested = false,
                )
            },
        )

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "observe-tree",
                name = "observe_screen",
                argumentsJson = "{}",
            ),
        )
        val content = JSONObject(result.content)

        assertFalse(checkNotNull(received).includeScreenshot)
        assertTrue(checkNotNull(received).includeUiTree)
        assertEquals(60, checkNotNull(received).maxNodes)
        assertEquals("o-tree", content.getString("observation_id"))
        assertFalse(content.getJSONObject("screenshot").getBoolean("requested"))
        assertTrue(result.images.isEmpty())
        tools.close()
    }

    @Test
    fun explicitScreenshotRefreshesTreeAndReturnsImage() {
        var received: AgentScreenObservationContract.Options? = null
        val image = AgentModelClient.ModelImage(
            reference = "data:image/webp;base64,AA==",
            mimeType = "image/webp",
            bytes = 1,
            width = 1080,
            height = 2400,
            source = "screen",
        )
        val tools = tools(
            screenObservationProvider = { options ->
                received = options
                observation(
                    observationId = "o-image",
                    screenshotRequested = true,
                    image = image,
                )
            },
        )

        val result = tools.execute(
            AgentModelClient.ToolCall(
                id = "observe-image",
                name = "observe_screen",
                argumentsJson = """{"include_screenshot":true}""",
            ),
        )

        assertTrue(checkNotNull(received).includeScreenshot)
        assertTrue(checkNotNull(received).includeUiTree)
        assertEquals("o-image", JSONObject(result.content).getString("observation_id"))
        assertEquals(listOf(image), result.images)
        tools.close()
    }

    @Test fun invalidOrRevokedCallsDoNotStartAccessibilityRecoveryOrAcquireGui() {
        val coordinator = GuiControlCoordinator()
        val control = coordinator.openRun("preflight", AgentRunController())
        tools(guiControl = control, beforeGuiAcquisition = { error("Must reject before recovery") }).use { executor ->
            listOf(
                "tap" to "{}", "tap" to "{\"x\":1,\"y\":1}",
                "type_text" to "{}", "tap_element" to "{\"index\":-1}",
                "press_key" to "{\"button\":\"PASTE\"}",
            ).forEach { (name, args) ->
                val result = JSONObject(executor.execute(AgentModelClient.ToolCall(name, name, args)).content)
                assertTrue(result.getString("code") in setOf("ROOT_REQUIRED", "INVALID_ARGUMENT"))
                assertEquals(null, coordinator.state.value.ownerRunId)
            }
        }
    }

    @Test fun recoveryRejectionClearsPublishedObservationBeforeNextCall() {
        val coordinator = GuiControlCoordinator()
        val control = coordinator.openRun("recovered", AgentRunController())
        val restored = AtomicBoolean(false)
        tools(guiControl = control, screenObservationProvider = { observation("observed", false) },
            beforeGuiAcquisition = { name ->
                if (name == "tap" && restored.compareAndSet(true, false))
                    ToolExecutionDecision.Reject("ACCESSIBILITY_RESTORED_REOBSERVE", "Reobserve")
                else ToolExecutionDecision.Allow
            }, beforeToolExecution = { name ->
                if (name == "observe_screen") ToolExecutionDecision.Allow else ToolExecutionDecision.Reject("ACTION_REACHED", "Boundary")
            }).use { executor ->
            fun call(name: String, args: String = "{}") = JSONObject(executor.execute(AgentModelClient.ToolCall(name, name, args)).content)
            assertTrue(call("observe_screen").getBoolean("ok"))
            restored.set(true)
            val tap = "{\"x\":1,\"y\":1,\"coordinate_space\":\"screen\"}"
            assertEquals("ACCESSIBILITY_RESTORED_REOBSERVE", call("tap", tap).getString("code"))
            assertEquals("NO_OBSERVATION", call("tap", tap).getString("code"))
            assertTrue(call("observe_screen").getBoolean("ok"))
            assertEquals("ACTION_REACHED", call("tap", tap).getString("code"))
        }
    }

    @Test fun transientDisconnectEvenWhenReconnectedInvalidatesSameOwnerObservation() {
        val coordinator = GuiControlCoordinator()
        val control = coordinator.openRun("transient", AgentRunController())
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        tools(guiControl = control, screenObservationProvider = { observation("observed", false) },
            accessibilityDisconnectCount = count::get,
            beforeToolExecution = { name -> if (name == "observe_screen") ToolExecutionDecision.Allow else ToolExecutionDecision.Reject("ACTION_REACHED", "Boundary") }
        ).use { executor ->
            fun call(name: String, args: String = "{}") = JSONObject(executor.execute(AgentModelClient.ToolCall(name, name, args)).content)
            assertTrue(call("observe_screen").getBoolean("ok"))
            count.incrementAndGet()
            assertEquals("NO_OBSERVATION", call("type_text", "{\"text\":\"stale\"}").getString("code"))
            assertTrue(call("observe_screen").getBoolean("ok"))
            assertEquals("ACTION_REACHED", call("type_text", "{\"text\":\"fresh\"}").getString("code"))
        }
    }

    @Test fun disconnectDuringObservationDoesNotPublishOrAttachStaleImage() {
        val coordinator = GuiControlCoordinator()
        val control = coordinator.openRun("observe-disconnect", AgentRunController())
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        tools(guiControl = control, accessibilityDisconnectCount = count::get,
            screenObservationProvider = { count.incrementAndGet(); observation("stale", false) }
        ).use { executor ->
            val result = executor.execute(AgentModelClient.ToolCall("observe", "observe_screen", "{}"))
            assertEquals("ACCESSIBILITY_OBSERVATION_INTERRUPTED", JSONObject(result.content).getString("code"))
            assertTrue(result.images.isEmpty())
        }
    }

    private fun tools(
        terminalEnabled: () -> Boolean = { false },
        browserEnabled: () -> Boolean = { false },
        memoryEnabled: () -> Boolean = { false },
        memoryWritable: Boolean = true,
        rootAvailable: () -> Boolean = { false },
        sensitiveReadEnabled: () -> Boolean = { false },
        screenObservationProvider: (
            (AgentScreenObservationContract.Options) -> RootShellDeviceController.Observation
        )? = null,
        beforeToolExecution: (String) -> ToolExecutionDecision = {
            ToolExecutionDecision.Allow
        },
        guiControl: GuiControlCoordinator.RunControl? = null,
        beforeGuiAcquisition: (String) -> ToolExecutionDecision = { ToolExecutionDecision.Allow },
        accessibilityDisconnectCount: () -> Int = { 0 },
    ): AgentLocalTools =
        AgentLocalTools(
            context = RuntimeEnvironment.getApplication() as Context,
            logger = NoOpLogger,
            browserRunId = "test-run",
            terminalToolsEnabled = terminalEnabled,
            browserToolsEnabled = browserEnabled,
            memoryToolsEnabled = memoryEnabled,
            memoryWritable = memoryWritable,
            rootAvailable = rootAvailable,
            deviceSensitiveReadToolsEnabled = sensitiveReadEnabled,
            screenObservationProvider = screenObservationProvider,
            beforeToolExecution = beforeToolExecution,
            guiControl = guiControl,
            beforeGuiAcquisition = beforeGuiAcquisition,
            accessibilityDisconnectCount = accessibilityDisconnectCount,
        )

    private fun observation(
        observationId: String,
        screenshotRequested: Boolean,
        image: AgentModelClient.ModelImage? = null,
    ): RootShellDeviceController.Observation {
        val elementObservation = RootShellDeviceController.ElementObservation(
            id = observationId,
            source = RootShellDeviceController.ElementSource.ACCESSIBILITY,
            packageName = "example.app",
            windowId = 1,
            nodes = emptyList(),
            maxNodes = 60,
            truncated = false,
        )
        val content = JSONObject()
            .put("ok", true)
            .put("tool", "observe_screen")
            .put("observation_id", observationId)
            .put(
                "screenshot",
                JSONObject()
                    .put("requested", screenshotRequested)
                    .put("attached", image != null),
            )
            .toString()
        return RootShellDeviceController.Observation(
            content = content,
            image = image,
            elementObservation = elementObservation,
            coordinateSpace = image?.let {
                RootShellDeviceController.CoordinateSpace(
                    screenWidth = 1080,
                    screenHeight = 2400,
                    screenshotWidth = checkNotNull(it.width),
                    screenshotHeight = checkNotNull(it.height),
                )
            },
        )
    }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
