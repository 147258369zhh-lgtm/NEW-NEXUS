package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.packages.*
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RuntimePackageActionsTest {
    private val tool = PackageTool("run", "Run fixture", "main.js", """{"type":"object","properties":{}}""")
    private val record = CapabilityPackage("fixture", "Fixture", "", "1", "plugin", "a".repeat(64), listOf(tool), enabled = true)

    @Test fun onlyInstalledReadonlyScreensCanUseBoundRenderingAndActionsKeepNormalExecutionAdmission() {
        val context = RuntimeEnvironment.getApplication()
        val module = PackageUiServiceTest().install()
        val arguments = JSONObject().put("state", JSONObject()).put("memo", JSONObject()).put("moduleId", module.id).put("moduleSpec", JSONObject())
        val render = RuntimePackageActions.request(module.record, module.tool, arguments)
        assertTrue(RuntimePackageActions.isReadOnlyPresentation(context, render))
        assertTrue(render.toolPolicy.isAllowed("read_file")); assertFalse(render.toolPolicy.isAllowed("write_file"))
        assertFalse(render.toolPolicy.isAllowed("request_user_input"))
        assertFalse(RuntimePackageActions.isReadOnlyPresentation(context, render.copy(toolPolicy = AgentToolPolicy.UNRESTRICTED)))
        val action = RuntimePackageActions.request(module.record, module.tool, JSONObject(arguments.toString()).put("actionId", "__action_1"))
        assertTrue(action.toolPolicy.isAllowed("write_file"))
        assertFalse(RuntimePackageActions.isReadOnlyPresentation(context, action))
        assertFalse(RuntimePackageActions.isReadOnlyPresentation(context, RuntimePackageActions.request(record, tool, JSONObject())))
        CapabilityPackageStore(context).configure(module.record.id, enabled = false)
        assertFalse(RuntimePackageActions.isReadOnlyPresentation(context, render))
    }

    @Test fun directActionUsesCanonicalPluginRunAndToolFactsWithoutAnyModelRequest() {
        val wire = RuntimePackageActions.request(record, tool, JSONObject())
        val roundtrip = AgentRuntimeWire.runRequestFromBundle(AgentRuntimeWire.toBundle(wire, emptyList()))
        assertEquals(wire.operation, roundtrip.operation)
        assertFalse(AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(roundtrip))
        val mapped = NexusRunMapper.fromLegacy(roundtrip).request
        assertEquals(RunSource.PLUGIN, mapped.source); assertEquals(RunMode.TASK, mapped.mode)
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val controller = AgentRunController()
            val recorder = NexusExecutionRecorder(store, mapped).apply { bindController(controller); start() }
            val result = RuntimePackageActions.execute(roundtrip, AgentModelClient.ToolExecutor { call ->
                assertEquals(PackageRunSnapshot.toolName(record.id, tool), call.name)
                AgentModelClient.ToolResult("{\"ok\":true,\"value\":42}", sensitive = true)
            }, AgentRunBudgetGuard(mapped.budget), controller, recorder::record)
            assertTrue(result.content.contains("42"))
            recorder.finish(AgentRuntimeWire.RunResult(wire.runId, true, result.content), false)
            assertEquals(RunState.SUCCEEDED, store.find(mapped.runId)!!.state)
            assertTrue(store.items(mapped.runId).any { it.kind == ItemKind.TOOL_CALL && it.state == RunState.SUCCEEDED })
            assertTrue(store.modelBudgetRequests(mapped.runId).isEmpty())
        }
    }

    @Test fun invalidActionAndExhaustedBudgetNeverReachAnExecutorAndFailureRemainsFailure() {
        val wire = RuntimePackageActions.request(record, tool, JSONObject())
        var calls = 0
        val executor = AgentModelClient.ToolExecutor { calls++; AgentModelClient.ToolResult("{\"ok\":false,\"code\":\"PACKAGE_TOOL_NOT_GRANTED\"}") }
        val guard = AgentRunBudgetGuard(RunBudget(hardToolCalls = 1)).apply { beforeToolCall() }
        assertThrows(AgentModelFailure::class.java) { RuntimePackageActions.execute(wire, executor, guard, AgentRunController()) {} }
        assertEquals(0, calls)
        val denied = assertThrows(AgentModelFailure::class.java) {
            RuntimePackageActions.execute(wire, executor, AgentRunBudgetGuard(RunBudget(hardToolCalls = 1)), AgentRunController()) {}
        }
        assertEquals("PACKAGE_TOOL_NOT_GRANTED", denied.code); assertEquals(1, calls)
        val invalid = wire.copy(handoff = wire.handoff!!.copy(payload = """{"tool":"terminal","arguments":{}}"""))
        assertThrows(IllegalArgumentException::class.java) { RuntimePackageActions.execute(invalid, executor, AgentRunBudgetGuard(RunBudget()), AgentRunController()) {} }
        assertEquals(1, calls)
    }

    @Test fun cancellationAfterExecutionKeepsTheActualReceiptWithoutRepeatingTheAction() {
        for (successful in listOf(true, false)) {
            val wire = RuntimePackageActions.request(record, tool, JSONObject())
            val mapped = NexusRunMapper.fromLegacy(wire).request
            NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
                val controller = AgentRunController()
                val recorder = NexusExecutionRecorder(store, mapped).apply { bindController(controller); start() }
                var calls = 0
                assertThrows(AgentRunCancelledException::class.java) {
                    RuntimePackageActions.execute(wire, AgentModelClient.ToolExecutor {
                        calls++
                        controller.cancel()
                        AgentModelClient.ToolResult("""{"ok":$successful}""")
                    }, AgentRunBudgetGuard(mapped.budget), controller, recorder::record)
                }
                recorder.finish(AgentRuntimeWire.RunResult(wire.runId, false, "cancelled"), true)
                assertEquals(1, calls)
                assertEquals(RunState.CANCELLED, store.find(mapped.runId)!!.state)
                assertEquals(if (successful) RunState.SUCCEEDED else RunState.FAILED,
                    store.items(mapped.runId).single { it.kind == ItemKind.TOOL_CALL }.state)
            }
        }
    }

    @Test fun malformedResultClosesTheToolAsFailedWithoutInventingSuccess() {
        val events = mutableListOf<AgentEvent>()
        val failure = assertThrows(AgentModelFailure::class.java) {
            RuntimePackageActions.execute(RuntimePackageActions.request(record, tool, JSONObject()),
                AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("not-json") },
                AgentRunBudgetGuard(RunBudget()), AgentRunController(), events::add)
        }
        assertEquals("PACKAGE_ACTION_INVALID_RESULT", failure.code)
        assertEquals(false, events.filterIsInstance<AgentEvent.ToolFinished>().single().success)
    }

    @Test fun conversationMenuActionsKeepTheirPluginSourceAndRealConversationOrMeetingIdentity() {
        val chat = io.github.mangi.eta.data.model.ConversationSubject.chat("chat-actual")
        val meeting = io.github.mangi.eta.data.model.ConversationSubject.meeting("meeting-actual")
        for (subject in listOf(chat, meeting)) {
            val request = RuntimePackageActions.request(record, tool, JSONObject(), subject)
            assertEquals(subject, RuntimePackageActions.subject(request.handoff))
            assertEquals(RunSource.PLUGIN, NexusRunMapper.fromLegacy(request).request.source)
            assertEquals(if (subject == chat) subject.id else subject.key, AgentConversationIdentity.fromHandoff(request.handoff, request.runId))
            val encoded = AgentRuntimeWire.runRequestFromBundle(AgentRuntimeWire.toBundle(request, emptyList()))
            assertEquals(subject, RuntimePackageActions.subject(encoded.handoff))
        }
        val bad = AgentRuntimeWire.EntryHandoff("bad", RuntimePackageActions.SOURCE, """{"subject_kind":"root","subject_id":"wrong"}""")
        assertThrows(IllegalStateException::class.java) { RuntimePackageActions.subject(bad) }
    }
}
