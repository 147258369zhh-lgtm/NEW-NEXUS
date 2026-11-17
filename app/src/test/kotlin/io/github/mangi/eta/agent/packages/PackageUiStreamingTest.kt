package io.github.mangi.eta.agent.packages

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentRunBudgetGuard
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.agent.capability.NexusRunHosts
import io.github.zhh_lgtm.nexus.application.api.InMemoryCapabilityDirectory
import io.github.zhh_lgtm.nexus.kernel.api.RunBudget
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PackageUiStreamingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun frame(module: PackageUiModule, sequence: Int = 1, value: JSONObject = PackageUiServiceTest().document(1)) =
        AgentEvent.PluginUiFrame(module.record.id, module.record.digest, module.id, sequence, value.toString())
    @Test fun uiFramesCrossTheOriginalWireButNeverEnterRunHistoryOrDurableCheckpoints() {
        val module = PackageUiServiceTest().install(); val event = frame(module)
        assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
        assertNull(event.replayProjection()); assertNull(event.recoveryProjection())
        assertTrue(runCatching { AgentEventJsonCodec.encode(event) }.isFailure)
        var recorded = 0; val live = mutableListOf<AgentEvent>(); val replay = mutableListOf<AgentEvent>()
        val session = AgentRuntimeSession("ui", eventSink = live::add, eventRecorder = { recorded++ })
        assertTrue(session.emit(event)); assertEquals(listOf(event), live); assertEquals(0, recorded)
        session.attach(replay::add, {})
        assertTrue(replay.isEmpty())
    }
    @Test fun originalPackageExecutorPublishesAValidatedFrameDuringTheActualActionBeforeItsFinalReceipt() {
        val fixture = PackageUiServiceTest(); val module = fixture.install()
        val controller = AgentRunController(); val events = mutableListOf<AgentEvent>(); var finished = false
        val args = JSONObject().put("state", JSONObject()).put("memo", JSONObject()).put("moduleId", module.id).put("moduleSpec", JSONObject())
            .put("actionId", "__action_1").put("expectedTree", fixture.document().getJSONObject("tree"))
        val runner = PackageHookRunner { source, _, _, host ->
            assertTrue(JSONObject(source.readText()).getBoolean("stream_mode"))
            host("package:ui-render", JSONObject().put("phase", "begin"))
            assertFalse(host("write_file", JSONObject()).getBoolean("ok"))
            host("package:ui-render", JSONObject().put("phase", "end"))
            assertTrue(host("package:ui-frame", fixture.document(1)).getBoolean("ok"))
            assertTrue(events.any { it is AgentEvent.PluginUiFrame }); assertFalse(finished)
            finished = true
            JSONObject().put("ok", true).put("value", fixture.document(2))
        }
        val request = RuntimePackageActions.request(module.record, module.tool, args)
        val selected = PackageRunSnapshot.toolName(module.record.id, module.tool)
        val snapshot = PackageRunSnapshot.load(context, selected, uiAction = true)
        NexusRunHosts(InMemoryCapabilityDirectory(emptyList()), snapshot.bindings(context, runner)).use { hosts ->
            val result = RuntimeInvocationScope.withController(controller) {
                RuntimePackageActions.execute(request, AgentModelClient.ToolExecutor(hosts::execute), AgentRunBudgetGuard(RunBudget()), controller, events::add)
            }
            assertTrue(result.content.contains("次数 2")); assertEquals(1, events.filterIsInstance<AgentEvent.PluginUiFrame>().size)
            assertEquals(true, events.filterIsInstance<AgentEvent.ToolFinished>().single().success)
        }
    }
    @Test fun viewsIgnoreForeignOutOfOrderLateRevokedAndUntrustedFramesWithoutExecutingEffects() {
        val fixture = PackageUiServiceTest(); val module = fixture.install(); val observed = mutableListOf<PackageUiDocument>()
        var sink: ((AgentEvent) -> Unit)? = null
        val service = PackageUiService(context, streamExecute = { _, _, _, _, event ->
            sink = event
            event(frame(module).copy(packageId = "foreign")); event(frame(module, 2))
            event(frame(module, 1)); event(frame(module, 2))
            JSONObject().put("ok", true).put("value", fixture.document(3)).toString()
        })
        val result = service.action(module, PackageUiDocument.parse(fixture.document()), "__action_1", null, AgentRunController(), onFrame = observed::add)
        assertEquals(1, observed.size); assertEquals(3, result.state.getInt("count"))
        sink!!.invoke(frame(module, 3)); assertEquals(1, observed.size)
        val bad = fixture.document().apply { getJSONArray("effects").put(JSONObject().put("type", "navigate").put("route", "fake")) }
        val reject = PackageUiService(context, streamExecute = { _, _, _, _, event ->
            event(frame(module, value = bad)); JSONObject().put("ok", true).put("value", fixture.document()).toString()
        })
        reject.action(module, PackageUiDocument.parse(fixture.document()), "__action_1", null, AgentRunController(), onFrame = observed::add)
        assertEquals(1, observed.size)
    }
}
