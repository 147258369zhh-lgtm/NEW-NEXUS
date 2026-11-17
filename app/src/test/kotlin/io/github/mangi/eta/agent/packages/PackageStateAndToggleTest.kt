package io.github.mangi.eta.agent.packages

import android.app.Application
import android.net.Uri
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PackageStateAndToggleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun args(action: String, revision: Long = 0, value: Any = true) = JSONObject().put("action", action).put("key", "enabled")
        .put("expectedRevision", revision).put("value", value)
    @Test fun stateSurvivesNewInstancesIsPackageScopedAndRejectsStaleWritesOversizeAndProbes() {
        val state = PackageStateStore(context)
        assertEquals(0L, state.invoke("one", args("get"), true).getLong("revision"))
        assertEquals(1L, state.invoke("one", args("put"), true).getLong("revision"))
        assertTrue(PackageStateStore(context).invoke("one", args("get"), false).getBoolean("value"))
        assertTrue(state.invoke("two", args("get"), true).isNull("value"))
        assertTrue(runCatching { state.invoke("one", args("put", 0, false), true) }.isFailure)
        assertTrue(runCatching { state.invoke("one", args("put", 1, false), false) }.isFailure)
        assertTrue(runCatching { state.invoke("one", args("put", 1, "x".repeat(70_000)), true) }.isFailure)
        assertTrue(state.invoke("one", args("get"), false).getBoolean("value"))
        assertTrue(runCatching { state.invoke("../outside", args("get"), true) }.isFailure)
        val path = File(context.filesDir, "capability-packages/state/one.json")
        path.writeText("corrupt fixture")
        assertTrue(runCatching { state.invoke("one", args("put", 1), true) }.isFailure)
        assertEquals("corrupt fixture", path.readText())
    }

    private fun installed(): CapabilityPackage {
        val store = CapabilityPackageStore(context)
        val archive = File.createTempFile("toggle-", ".toolpkg", context.cacheDir)
        ZipOutputStream(archive.outputStream()).use { zip ->
            mapOf("manifest.json" to """{"toolpkg_id":"fixture.toggle","main":"main.js","api_version":"1.0.0"}""", "main.js" to "exports.registerToolPkg=()=>true;").forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        val record = store.install(store.stage(Uri.fromFile(archive)))
        store.saveRegistration(record, JSONArray().put(JSONObject().put("kind", "InputMenuTogglePlugin").put("id", "switch")
            .put("function", JSONObject().put("nexus_callback", "InputMenuTogglePlugin/switch/function"))))
        store.configure(record.id, enabled = true, enabledExtensions = setOf("InputMenuTogglePlugin"))
        return store.list().single()
    }

    @Test fun actualInstalledToggleCallbackUsesTheOriginalNoModelRequestAndPersistentStateThenUninstallClearsIt() = runBlocking {
        val record = installed(); val state = PackageStateStore(context); val subject = ConversationSubject.chat("chat")
        val service = PackageInputToggles(context) { current, tool, event, controller, owner ->
            assertEquals(subject, owner)
            val request = RuntimePackageActions.request(current, tool, event, owner)
            assertEquals(AgentRuntimeWire.OP_PACKAGE_ACTION, request.operation)
            assertFalse(request.toolPolicy.isAllowed("write_file"))
            val before = state.invoke(current.id, args("get"), false)
            if (event.getJSONObject("eventPayload").getString("action") == "toggle") {
                assertEquals("planning", event.getJSONObject("eventPayload").getString("toggleId"))
                state.invoke(current.id, args("put", before.getLong("revision"), !before.optBoolean("value")), true)
            }
            val row = JSONObject().put("id", "planning").put("title", "计划模式").put("isChecked", state.invoke(current.id, args("get"), false).optBoolean("value"))
            JSONObject().put("ok", true).put("value", JSONObject().put("toggles", JSONArray().put(row))).toString()
        }
        val controller = AgentRunController()
        assertTrue(service.available())
        val first = service.load(subject, controller).single()
        assertFalse(first.checked)
        assertTrue(service.toggle(first, subject, controller).single().checked)
        assertTrue(service.load(subject, controller).single().checked)
        CapabilityPackageStore(context).configure(record.id, enabledExtensions = emptySet())
        assertFalse(service.available())
        assertTrue(runCatching { service.toggle(first, subject, controller) }.isFailure)
        CapabilityPackageStore(context).delete(record.id)
        assertTrue(state.invoke(record.id, args("get"), false).isNull("value"))
    }

    @Test fun hookNativeReceiptsStayCommittedWhenCancellationArrivesJustAfterTheToolResult() {
        NexusExecutionStore(context).use { store ->
            val run = RunRequest(RunId("hook-receipt"), RunSource.CHAT, RunMode.CHAT, HostId("native-android"))
            val recorder = NexusExecutionRecorder(store, run); val controller = AgentRunController()
            recorder.bindController(controller); recorder.start()
            val result = PackageHookToolReceipts.execute(AgentModelClient.ToolCall("inside-hook", "write_file", "{}"),
                AgentModelClient.ToolExecutor { controller.cancel(); AgentModelClient.ToolResult("{\"ok\":true,\"receipt\":\"saved\"}") }, recorder::record)
            assertEquals("saved", JSONObject(result.content).getString("receipt"))
            assertEquals(RunState.SUCCEEDED, store.items(run.runId).single { it.kind == ItemKind.TOOL_CALL }.state)
            recorder.finish(AgentRuntimeWire.RunResult("hook-receipt", false, "", "cancelled"), true)
        }
    }
}
