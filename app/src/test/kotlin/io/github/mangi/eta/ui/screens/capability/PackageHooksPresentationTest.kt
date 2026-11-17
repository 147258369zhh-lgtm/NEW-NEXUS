package io.github.mangi.eta.ui.screens.capability

import android.app.Application
import android.net.Uri
import androidx.compose.foundation.layout.Column
import io.github.mangi.eta.agent.packages.*
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.ui.screens.memory.MemoryLearningPresentationTest
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Text

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w320dp-h840dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackageHooksPresentationTest {
    private fun installed(): CapabilityPackage {
        val context = RuntimeEnvironment.getApplication(); val store = CapabilityPackageStore(context)
        val archive = File.createTempFile("hook-ui-", ".toolpkg", context.cacheDir)
        ZipOutputStream(archive.outputStream()).use { zip ->
            mapOf("manifest.json" to """{"toolpkg_id":"fixture.ui-hooks","display_name":"扩展检查","main":"main.js"}""", "main.js" to "exports.registerToolPkg=()=>true;").forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
            }
        }
        val record = store.install(store.stage(Uri.fromFile(archive)))
        val hook = JSONObject().put("kind", "SystemPromptComposeHook").put("id", "system").put("function", JSONObject().put("nexus_callback", "SystemPromptComposeHook/system/function"))
        val menu = JSONObject().put("kind", "ChatMessageMenuItem").put("id", "note").put("title", "保存回复笔记")
            .put("function", JSONObject().put("nexus_callback", "ChatMessageMenuItem/note/function"))
        store.saveRegistration(record, JSONArray().put(hook).put(menu))
        store.configure(record.id, enabled = true)
        return store.list().single()
    }

    @Test fun extensionSettingsUseTheExistingPackageDialogAndActualSwitchCanBeSaved() {
        val record = installed()
        MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.3f) { PackageCenterScreen(onBack = {}) }.use { panel ->
            panel.await { panel.texts().contains(record.title) }; panel.click(record.title)
            panel.await { panel.texts().contains("扩展") }; panel.click("扩展")
            panel.await { panel.texts().contains("系统提示词组合") }; panel.capture("package-hooks-settings-narrow-dark")
            panel.click("系统提示词组合"); panel.click("保存")
            panel.await { CapabilityPackageStore(RuntimeEnvironment.getApplication()).list().first { it.id == record.id }.enabledExtensions.contains("SystemPromptComposeHook") }
        }
    }

    @Test fun anInstalledReplyMenuFunctionAppearsAtTheExistingConversationPluginEntry() {
        installed()
        MemoryLearningPresentationTest.Panel(dark = true) {
            PackageConversationEntry(ConversationSubject.chat("actual-chat")) { Column {
                Text("会话内容"); ConversationPluginMessageButton("actual-message", "reply contents")
            } }
        }.use { panel ->
            panel.await { CapabilityPackageStore(RuntimeEnvironment.getApplication()).conversationActions("ChatMessageMenuItem").isNotEmpty() }
            panel.clickDescription("对此回复使用插件")
            panel.await { panel.texts().contains("保存回复笔记") }
            panel.capture("package-reply-menu-narrow-dark")
        }
    }

    @Test fun inputToggleUsesExistingPluginDialogWithoutMovingTheConversationLayout() {
        val record = installed()
        val context = RuntimeEnvironment.getApplication()
        val store = CapabilityPackageStore(context)
        val registration = JSONObject().put("kind", "InputMenuTogglePlugin").put("id", "planning")
            .put("function", JSONObject().put("nexus_callback", "InputMenuTogglePlugin/planning/function"))
        store.saveRegistration(record, JSONArray(record.registrations).put(registration))
        store.configure(record.id, enabledExtensions = setOf("InputMenuTogglePlugin"))
        var checked = false
        val toggles = PackageInputToggles(context) { _, _, args, _, _ ->
            if (args.getJSONObject("eventPayload").getString("action") == "toggle") checked = !checked
            JSONObject().put("ok", true).put("value", JSONArray().put(JSONObject().put("id", "planning")
                .put("title", "计划模式").put("isChecked", checked))).toString()
        }
        MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.3f) {
            PackageConversationEntry(ConversationSubject.chat("chat-toggles"), inputToggleService = toggles) {
                val actions = LocalPackageConversationActions.current
                Column { Text("会话内容"); top.yukonga.miuix.kmp.basic.TextButton(text = "插件操作", onClick = { actions?.composer?.invoke() }) }
            }
        }.use { panel ->
            panel.await { store.list().single().enabledExtensions.contains("InputMenuTogglePlugin") }
            panel.click("插件操作")
            panel.await { panel.texts().contains("计划模式") }
            panel.click("计划模式")
            panel.await { checked }
            panel.capture("package-input-toggle-narrow-dark")
        }
    }
}
