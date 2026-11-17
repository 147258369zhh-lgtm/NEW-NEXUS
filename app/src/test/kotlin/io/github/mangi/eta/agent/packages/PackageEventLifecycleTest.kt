package io.github.mangi.eta.agent.packages

import android.app.Activity
import android.app.Application
import android.net.Uri
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentRunBudgetGuard
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.mangi.eta.ui.app.AgentConversationStore
import io.github.mangi.eta.ui.model.*
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PackageEventLifecycleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun row(kind: String, id: String = kind.lowercase(), event: String = "") = JSONObject().put("kind", kind).put("id", id)
        .put("event", event).put("function", JSONObject().put("nexus_callback", "$kind/$id/function"))
    private fun install(vararg rows: JSONObject): CapabilityPackage {
        val store = CapabilityPackageStore(context)
        val archive = File.createTempFile("event-", ".toolpkg", context.cacheDir)
        ZipOutputStream(archive.outputStream()).use { zip ->
            mapOf("manifest.json" to """{"toolpkg_id":"fixture.events","main":"main.js","api_version":"1.0.0"}""", "main.js" to "exports.registerToolPkg=()=>true;").forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry()
            }
        }
        val record = store.install(store.stage(Uri.fromFile(archive)))
        store.saveRegistration(record, JSONArray(rows.toList()))
        store.configure(record.id, enabled = true, allowedTools = setOf("read_file", "write_file", "terminal", "request_user_input"),
            enabledExtensions = rows.map { it.getString("kind") }.toSet())
        return store.list().single()
    }
    @After fun close() { PackageApplicationEvents.closeForTests(); EtaDatabase.closeForTests(); NexusExecutionStore.closeForTests() }

    @Test fun automaticHooksStayOutOfTheModelCatalogAndDeliveryUsesOriginalNoModelRuntimeAndReadOnlyPolicy() {
        val record = install(row("AppLifecycleHook", event = "application_on_foreground"), row("ChatMessageHook"))
        val selected = PackageEventDispatcher.select(listOf(record), "AppLifecycleHook", "application_on_foreground", JSONObject(), null).single()
        val ordinary = JSONArray(); PackageRunSnapshot.load(context).appendModelTools(ordinary)
        assertFalse(ordinary.toString().contains(PackageRunSnapshot.toolName(record.id, selected.tool)))
        val request = RuntimePackageActions.request(record, selected.tool, selected.arguments)
        assertEquals(RunSource.PLUGIN, NexusRunMapper.fromLegacy(request).request.source)
        assertTrue(request.toolPolicy.isAllowed("read_file")); assertFalse(request.toolPolicy.isAllowed("write_file")); assertFalse(request.toolPolicy.isAllowed("terminal"))
        assertFalse(request.toolPolicy.isAllowed("request_user_input"))
        val snapshot = PackageRunSnapshot.load(context, JSONObject(request.handoff!!.payload).getString("tool"))
        val schemas = JSONArray(); snapshot.appendModelTools(schemas)
        assertTrue(schemas.toString().contains(PackageRunSnapshot.toolName(record.id, selected.tool)))
        NexusExecutionStore(context).use { store ->
            val recorder = NexusExecutionRecorder(store, NexusRunMapper.fromLegacy(request).request); val controller = AgentRunController()
            recorder.bindController(controller); recorder.start()
            val result = RuntimePackageActions.execute(request, AgentModelClient.ToolExecutor { call ->
                assertEquals("application_on_foreground", JSONObject(call.argumentsJson).getString("eventName"))
                AgentModelClient.ToolResult("{\"ok\":true}")
            }, AgentRunBudgetGuard(RunBudget(hardToolCalls = 5)), controller, recorder::record)
            recorder.finish(AgentRuntimeWire.RunResult(request.runId, true, result.content), false)
            assertEquals(1, store.items(recorder.request.runId).count { it.kind == ItemKind.TOOL_CALL })
        }
        CapabilityPackageStore(context).configure(record.id, enabledExtensions = emptySet())
        assertTrue(PackageEventDispatcher.select(CapabilityPackageStore(context).list(), "AppLifecycleHook", "application_on_foreground", JSONObject(), null).isEmpty())
        assertTrue(PackageEventDispatcher.select(listOf(record), "AppLifecycleHook", "activity_on_resume", JSONObject(), null).isEmpty())
    }

    @Test fun realApplicationCallbacksTrackMultipleActivitiesWithoutFalseBackgroundTransitions() = runBlocking {
        install(row("AppLifecycleHook", "foreground", "application_on_foreground"), row("AppLifecycleHook", "background", "application_on_background"))
        val events = Collections.synchronizedList(mutableListOf<String>())
        PackageApplicationEvents.initialize(context) { events += it.arguments.getString("eventName") }
        val lifecycle = PackageAppLifecycle(context); lifecycle.install()
        val one = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        val two = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        one.pause().stop().destroy()
        withTimeout(3000) { PackageApplicationEvents.awaitIdle() }
        assertEquals(listOf("application_on_foreground"), events.toList())
        two.pause().stop().destroy()
        withTimeout(3000) { PackageApplicationEvents.awaitIdle() }
        assertEquals(listOf("application_on_foreground", "application_on_background"), events.toList())
        context.unregisterActivityLifecycleCallbacks(lifecycle)
    }

    @Test fun actualConversationCommitSendsOnlyNewFinishedMessagesAndRewritesDoNotRepeatUnchangedSuffixes() = runBlocking {
        install(row("ChatMessageHook"))
        val events = Collections.synchronizedList(mutableListOf<PackageEventDelivery>())
        PackageApplicationEvents.initialize(context) { events += it }
        var state = AgentChatHomeUiState(messages = listOf(UserMessageUi("user", "Question"), AgentMessageUi("answer", "First", isStreaming = true)),
            input = "", isStreaming = true, thinkingEnabled = false)
        suspend fun save() {
            AgentConversationStore.save(context, "chat", mapOf("chat" to state), mapOf("chat" to "Chat"), mapOf("chat" to 100L))
            withTimeout(3000) { PackageApplicationEvents.awaitIdle() }
        }
        save()
        assertEquals(listOf("user"), events.map { it.arguments.getJSONObject("eventPayload").getString("messageId") })
        state = state.copy(messages = listOf(UserMessageUi("user", "Question"), AgentMessageUi("answer", "Final")), isStreaming = false)
        save(); save()
        assertEquals(listOf("user", "answer"), events.map { it.arguments.getJSONObject("eventPayload").getString("messageId") })
        assertEquals("Final", events.last().arguments.getJSONObject("eventPayload").getString("content"))
        assertEquals(ConversationSubject.chat("chat"), events.last().subject)
        state = state.copy(messages = state.messages + AgentMessageUi("same-content", "Unchanged at completion", isStreaming = true))
        save()
        assertEquals(2, events.size)
        state = state.copy(messages = state.messages.map { if (it is AgentMessageUi && it.id == "same-content") it.copy(isStreaming = false) else it })
        save(); save()
        assertEquals(3, events.size)
        assertEquals("same-content", events.last().arguments.getJSONObject("eventPayload").getString("messageId"))
    }
}
