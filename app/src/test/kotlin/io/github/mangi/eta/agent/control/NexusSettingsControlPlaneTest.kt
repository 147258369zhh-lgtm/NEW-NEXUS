package io.github.mangi.eta.agent.control

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NexusSettingsControlPlaneTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val plane get() = NexusSettingsControlPlane.get(context)
    @Before fun prepare() = runBlocking {
        SettingsDataStore.init(context)
        SettingsDataStore.updateSettings { io.github.mangi.eta.data.model.Settings() }
        SettingsDataStore.setSpeechSettings(SpeechSettings())
    }

    @Test fun actualMemoryUiRepositorySharesTheAgentControlPlaneAndRollback() = runBlocking {
        AgentMemoryRepository.setEnabled(false)
        assertFalse(SettingsDataStore.settings().memoryEnabled)
        val original = plane.audit().single { it.optString("operation") == "execute" }
        assertEquals(SettingsActorKind.LOCAL_USER.name, original.getString("actor"))
        assertEquals("APPLIED", original.getString("phase"))
        assertTrue(original.getJSONObject("before").getBoolean("enabled"))
        assertFalse(original.getJSONObject("after").getBoolean("enabled"))
        assertTrue(original.getBoolean("rollbackSafe"))
        val restored = plane.rollback(original.getString("id"), SettingsActorKind.LOCAL_USER)
        assertTrue(restored.changed)
        assertTrue(SettingsDataStore.settings().memoryEnabled)
        assertTrue(EtaAppControlAuditStore(context).find(original.getString("id"))!!.getBoolean("undone"))
    }

    @Test fun laterUiChangesPreventAnOlderRollbackFromOverwritingThem() = runBlocking {
        AppearanceSettingsRepository.update { it.copy(themeMode = AppearanceThemeMode.DARK) }
        val first = plane.audit().last { it.getString("operation") == "execute" }
        AppearanceSettingsRepository.update { it.copy(themeMode = AppearanceThemeMode.LIGHT) }
        val failure = runCatching { plane.rollback(first.getString("id"), SettingsActorKind.LOCAL_USER) }.exceptionOrNull()
        assertEquals("SETTINGS_CHANGED_SINCE_AUDIT", failure?.message)
        assertEquals(AppearanceThemeMode.LIGHT, SettingsDataStore.settings().appearance.themeMode)
    }

    @Test fun concurrentUiAppearanceEditsPreserveBothFieldsAndHaveSeparateAuditIds() = runBlocking {
        coroutineScope {
            val one = async(Dispatchers.IO) { AppearanceSettingsRepository.update { it.copy(themeMode = AppearanceThemeMode.DARK) } }
            val two = async(Dispatchers.IO) { AppearanceSettingsRepository.update { it.copy(pureBlackEnabled = true) } }
            one.await(); two.await()
        }
        val actual = SettingsDataStore.settings().appearance
        assertEquals(AppearanceThemeMode.DARK, actual.themeMode)
        assertTrue(actual.pureBlackEnabled)
        assertEquals(2, plane.audit().count { it.getString("operation") == "execute" })
    }

    @Test fun actualAgentToolCarriesItsRunIdentityAndCanUndoItsOwnChange() {
        NexusExecutionStore(context).use { store ->
            val request = RunRequest(RunId("settings-agent"), RunSource.CHAT, RunMode.CHAT, HostId("native-android"))
            val recorder = NexusExecutionRecorder(store, request); val controller = AgentRunController()
            recorder.bindController(controller); recorder.start()
            val changed = RuntimeInvocationScope.withController(controller) {
                JSONObject(EtaAppControlTool.execute(context, JSONObject().put("action", "execute")
                    .put("id", "settings.memory.enabled").put("parameters", JSONObject().put("enabled", false))))
            }
            assertTrue(changed.toString(), changed.getBoolean("ok"))
            val audit = EtaAppControlAuditStore(context).find(changed.getString("auditId"))!!
            assertEquals("settings-agent", audit.getString("sourceRunId"))
            assertEquals(SettingsActorKind.LOCAL_AGENT.name, audit.getString("actor"))
            val undo = RuntimeInvocationScope.withController(controller) {
                JSONObject(EtaAppControlTool.execute(context, JSONObject().put("action", "undo").put("auditId", changed.getString("auditId"))))
            }
            assertTrue(undo.toString(), undo.getBoolean("ok"))
            recorder.finish(AgentRuntimeWire.RunResult("settings-agent", true, "fixture"), false)
        }
    }

    @Test fun remoteActorCannotChangeReadOrUndoLocalSettingsEvenWithForgedConfirmation() = runBlocking {
        assertTrue(runCatching { plane.read("settings.memory.enabled", SettingsActorKind.REMOTE_AGENT) }.exceptionOrNull() is EtaControlException)
        assertTrue(runCatching {
            plane.applyJson("settings.memory.enabled", JSONObject().put("enabled", false).put("confirmed", true), SettingsActorKind.REMOTE_AGENT)
        }.exceptionOrNull() is EtaControlException)
        assertTrue(SettingsDataStore.settings().memoryEnabled)
        assertNull(EtaAppControlConfirmation.pending.value)
        assertTrue(plane.list().all { it.id.startsWith("settings.") })
        assertNull(plane.describe("navigation.models"))
    }

    @Test fun speechUiWritesRemainUsableAndCanBeReadAndRolledBackThroughTheSamePlane() = runBlocking {
        SpeechSettingsRepository.save(SpeechSettings(chatAutoSpeak = true, localTtsVoiceId = "fixture-voice"))
        val original = plane.audit().single { it.optString("operation") == "execute" }
        assertEquals("settings.speech", original.getString("capabilityId"))
        assertTrue(plane.readJson("settings.speech", SettingsActorKind.LOCAL_USER).getBoolean("chatAutoSpeak"))
        plane.rollback(original.getString("id"), SettingsActorKind.LOCAL_USER)
        assertFalse(SettingsDataStore.speechSettings().chatAutoSpeak)
    }

    @Test fun unreadableAuditStoragePreventsWritingASetting() = runBlocking {
        val audit = File(context.filesDir, "eta_app_control/audit.json")
        audit.parentFile!!.mkdirs(); audit.writeText("corrupt fixture")
        assertTrue(runCatching { AgentMemoryRepository.setEnabled(false) }.isFailure)
        assertTrue(SettingsDataStore.settings().memoryEnabled)
        assertEquals("corrupt fixture", audit.readText())
    }

    @Test fun memoryPolicyUsesExistingStoreAndRejectsInvalidValuesBeforeChangingIt() = runBlocking {
        val manager = runCatching { androidx.work.WorkManager.getInstance(context) }.getOrElse {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
            androidx.work.WorkManager.getInstance(context)
        }
        fun policy() = io.github.mangi.eta.agent.memory.MemoryAutomationSettingsStore.read(context)
        val originalPolicy = policy()
        val original = JSONObject(kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(originalPolicy))
        val next = JSONObject(original.toString()).put("dailyLimit", 12).put("idleEnabled", false)
        val applied = plane.applyJson("settings.memory.automation", JSONObject().put("value", next), SettingsActorKind.LOCAL_USER)
        assertEquals(12, io.github.mangi.eta.agent.memory.MemoryAutomationSettingsStore.read(context).dailyLimit)
        val invalid = JSONObject(next.toString()).put("dailyLimit", 0)
        assertTrue(runCatching { plane.applyJson("settings.memory.automation", JSONObject().put("value", invalid), SettingsActorKind.LOCAL_USER) }.isFailure)
        assertEquals(originalPolicy.copy(dailyLimit = 12, idleEnabled = false), policy())
        assertEquals(12, plane.readJson("settings.memory.automation", SettingsActorKind.LOCAL_USER).getInt("dailyLimit"))
        plane.rollbackJson(applied.getString("auditId"), SettingsActorKind.LOCAL_USER)
        assertEquals(originalPolicy, policy())
        manager.cancelAllWork()
        Unit
    }

    @Test fun mcpSettingUndoDoesNotReplaceCredentialsOrOtherServerFields() = runBlocking {
        val repository = io.github.mangi.eta.data.repository.McpServerRepository
        repository.init(context)
        repository.addUnconfigured(io.github.mangi.eta.data.model.McpServerSetting("test-server", "Fixture", "https://example.com/mcp",
            tools = listOf(io.github.mangi.eta.data.model.McpToolDefinition("read", inputSchemaJson = "{}"))))
        val parameters = JSONObject().put("serverId", "test-server").put("toolName", "read").put("enabled", true)
        val applied = plane.applyJson("settings.mcp_tool", parameters, SettingsActorKind.LOCAL_USER)
        assertTrue(repository.serverById("test-server")!!.enabledToolNames.contains("read"))
        plane.rollbackJson(applied.getString("auditId"), SettingsActorKind.LOCAL_USER)
        val server = repository.serverById("test-server")!!
        assertTrue(server.enabledToolNames.isEmpty())
        assertEquals("https://example.com/mcp", server.url)
        assertEquals("read", server.tools.single().name)
    }

    @Test fun apiConnectorSwitchesUseExistingServiceAndRejectStaleOperations() = runBlocking {
        val store = io.github.mangi.eta.agent.capability.ApiConnectorStore(context)
        val original = store.load()
        val connector = io.github.mangi.eta.agent.capability.ApiConnectorDefinition(
            id = "control-api-test", name = "Reports", baseUrl = "https://example.com",
            actions = listOf(io.github.mangi.eta.agent.capability.ApiConnectorAction(
                name = "read_report", description = "Read", method = "GET", path = "/reports",
                inputSchemaJson = "{\"type\":\"object\"}", enabled = false,
            )),
        )
        try {
            store.save(listOf(connector))
            val state = EtaAppControlRegistry.get(context, "state.api_connectors")
                .getJSONArray("connectors").getJSONObject(0)
            val action = state.getJSONArray("actions").getJSONObject(0)
            val args = JSONObject().put("connectorId", connector.id).put("actionName", "read_report")
                .put("enabled", true).put("expectedEnabled", action.getBoolean("enabled"))
                .put("expectedBinding", action.getString("binding"))
            val applied = plane.applyJson("settings.api_connector.action", args, SettingsActorKind.LOCAL_USER)
            assertTrue(store.load().single().actions.single().enabled)
            assertFalse(applied.toString().contains("example.com"))
            assertTrue(runCatching { plane.applyJson("settings.api_connector.action", args, SettingsActorKind.LOCAL_USER) }.isFailure)
            plane.rollbackJson(applied.getString("auditId"), SettingsActorKind.LOCAL_USER)
            assertFalse(store.load().single().actions.single().enabled)

            val latest = EtaAppControlRegistry.get(context, "state.api_connectors")
                .getJSONArray("connectors").getJSONObject(0)
            val disable = JSONObject().put("connectorId", connector.id).put("enabled", false)
                .put("expectedEnabled", latest.getBoolean("enabled")).put("expectedBinding", latest.getString("binding"))
            val disabled = plane.applyJson("settings.api_connector.enabled", disable, SettingsActorKind.LOCAL_USER)
            assertFalse(store.load().single().enabled)
            store.upsert(store.load().single().copy(name = "Changed elsewhere"))
            assertEquals("SETTINGS_CHANGED_SINCE_AUDIT", runCatching {
                plane.rollbackJson(disabled.getString("auditId"), SettingsActorKind.LOCAL_USER)
            }.exceptionOrNull()?.message)
            assertFalse(store.load().single().enabled)
        } finally {
            store.save(original)
        }
    }
}
