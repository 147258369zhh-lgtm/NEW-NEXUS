package io.github.mangi.eta.agent.packages

import android.app.Application
import android.net.Uri
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.agent.capability.NexusRunHosts
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.zhh_lgtm.nexus.application.api.InMemoryCapabilityDirectory
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PackageUiServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    internal fun install(messageDialog: Boolean = false, secondRoute: Boolean = false, assets: Map<String, ByteArray> = emptyMap()): PackageUiModule {
        val store = CapabilityPackageStore(context)
        val archive = File.createTempFile("ui-", ".toolpkg", context.cacheDir)
        ZipOutputStream(archive.outputStream()).use { zip ->
            mapOf("manifest.json" to """{"toolpkg_id":"fixture.ui","main":"main.js","api_version":"1.0.0"}""", "main.js" to "exports.registerToolPkg=()=>true;",
                "ui/panel.js" to "exports.default=ctx=>ctx.UI.Text({text:'Fixture'});").forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
            }
            assets.forEach { (path, bytes) -> zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
        }
        val record = store.install(store.stage(Uri.fromFile(archive)))
        val registrations = JSONArray().put(if (messageDialog) JSONObject().put("kind", "ChatMessageMenuItem").put("id", "translate")
            .put("title", "翻译消息").put("function", JSONObject().put("nexus_callback", "ChatMessageMenuItem/translate/function"))
            .put("dialog", JSONObject().put("screen", "ui/panel.js").put("title", "翻译消息"))
        else JSONObject().put("kind", "UiRoute").put("id", "settings").put("title", "插件设置").put("route", "settings").put("screen", "ui/panel.js"))
        if (secondRoute) registrations.put(JSONObject().put("kind", "UiRoute").put("id", "details").put("title", "插件详情").put("screen", "ui/panel.js"))
        store.saveRegistration(record, registrations)
        store.configure(record.id, enabled = true, allowedTools = setOf("read_file", "write_file", "request_user_input"))
        return PackageUiService(context).modules().first()
    }
    fun document(count: Int = 0): JSONObject {
        fun node(type: String, props: JSONObject, children: JSONArray = JSONArray()) = JSONObject().put("__composeNode", true).put("type", type)
            .put("props", props).put("children", children).put("slots", JSONObject())
        val label = node("Text", JSONObject().put("text", "次数 $count"))
        val button = node("Button", JSONObject().put("text", "增加").put("onClick", JSONObject().put("__actionId", "__action_1")))
        return JSONObject().put("tree", node("Column", JSONObject(), JSONArray().put(label).put(button)))
            .put("state", JSONObject().put("count", count)).put("memo", JSONObject()).put("effects", JSONArray())
    }

    @Test fun installedModuleUsesOriginalIsolatedPayloadAndRenderCannotPerformWritesOrOpenUserQuestions() {
        val module = install()
        val schema = JSONArray(); PackageRunSnapshot.load(context).appendModelTools(schema)
        assertFalse(schema.toString().contains(PackageRunSnapshot.toolName(module.record.id, module.tool)))
        var writes = 0
        val runner = PackageHookRunner { file, _, _, host ->
            val payload = JSONObject(file.readText())
            assertEquals("__nexus_ui__", payload.getString("function")); assertEquals("ui/panel.js", payload.getString("entry"))
            assertTrue(payload.getJSONObject("scripts").has("ui/panel.js"))
            assertFalse(host("write_file", JSONObject()).getBoolean("ok"))
            assertFalse(host("request_user_input", JSONObject()).getBoolean("ok"))
            assertTrue(host("read_file", JSONObject()).getBoolean("ok"))
            JSONObject().put("ok", true).put("value", document())
        }
        val selected = PackageRunSnapshot.toolName(module.record.id, module.tool)
        val snapshot = PackageRunSnapshot.load(context, selected)
        NexusRunHosts(InMemoryCapabilityDirectory(emptyList()), snapshot.bindings(context, runner)).use { hosts ->
            val owner = AgentRunController()
            val api = PackageUiService(context) { item, args, controller, subject ->
                val request = RuntimePackageActions.request(item.record, item.tool, args, subject)
                assertEquals(RunSource.PLUGIN, NexusRunMapper.fromLegacy(request).request.source)
                RuntimeInvocationScope.withController(controller) { RuntimeInvocationScope.withNestedTools(AgentModelClient.ToolExecutor { call ->
                    if (call.name == "write_file") writes++
                    AgentModelClient.ToolResult("{\"ok\":true}")
                }) { hosts.execute(AgentModelClient.ToolCall("render", selected, args.toString())).content } }
            }
            val result = api.render(module, controller = owner, subject = ConversationSubject.chat("chat"))
            assertEquals(0, writes); assertEquals(setOf("__action_1"), result.actions)
        }
    }

    @Test fun moduleActionsAreExplicitStateReturnsAreParsedAndDisablingOrUpdatingRejectsOldActions() {
        val module = install(); var called = 0
        val api = PackageUiService(context) { _, args, _, _ ->
            called++
            if (args.has("actionId")) {
                assertEquals("__action_1", args.getString("actionId"))
                assertNotNull(args.optJSONObject("expectedTree"))
            }
            JSONObject().put("ok", true).put("value", document(if (args.has("actionId")) 1 else 0)).toString()
        }
        val owner = AgentRunController(); val first = api.render(module, controller = owner)
        val next = api.action(module, first, "__action_1", null, owner)
        assertEquals(1, next.state.getInt("count"))
        assertTrue(runCatching { api.action(module, next, "__action_999", null, owner) }.isFailure)
        CapabilityPackageStore(context).configure(module.record.id, enabled = false)
        assertTrue(runCatching { api.action(module, next, "__action_1", null, owner) }.isFailure)
        assertEquals(2, called)
    }

    @Test fun malformedOrUnsupportedTreesDoNotPretendToBeWorkingScreens() {
        val bad = document()
        bad.getJSONObject("tree").put("type", "UnsupportedHostObject")
        assertTrue(runCatching { PackageUiDocument.parse(bad) }.exceptionOrNull()?.message.orEmpty().contains("尚未适配"))
        val polluted = document().put("state", JSONObject().put("__proto__", JSONObject()))
        assertTrue(runCatching { PackageUiDocument.parse(polluted) }.isFailure)
        assertTrue(runCatching { PackageUiDocument.parse(document().put("effects", JSONArray().put(JSONObject().put("type", "executeRoot")))) }.isFailure)
        assertTrue(runCatching { PackageUiDocument.parse(document().put("memo", JSONObject().put("huge", "x".repeat(70_000)))) }.isFailure)
    }

    @Test fun messageDialogDeclarationCreatesADistinctScreenAndPreservesTheSelectedMessageState() {
        val module = install(messageDialog = true)
        assertEquals("translate", module.messageMenuId)
        assertTrue(module.tool.callbackKey!!.startsWith("UiRoute/"))
        val service = PackageUiService(context) { _, args, _, _ ->
            assertEquals("selected-message", args.getJSONObject("state").getJSONObject("message").getString("id"))
            JSONObject().put("ok", true).put("value", document()).toString()
        }
        service.render(module, state = JSONObject().put("message", JSONObject().put("id", "selected-message").put("content", "selected text")),
            controller = AgentRunController())
        assertTrue(CapabilityPackageStore(context).list().single().tools.any { it.callbackKey == "ChatMessageMenuItem/translate/function" })
        assertFalse(CapabilityPackageStore(context).list().single().issues.contains("UI_MODULES_PENDING"))
    }
}
