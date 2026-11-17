package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.os.*
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.voice.*
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.repository.NexusExecutionStore
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RuntimeVoiceSupplementTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture",
        systemPrompt = "fixture", supportsTools = false, autoCompactionEnabled = false)

    @Test fun microphoneSupplementIsAcceptedThroughTheServiceAndConsumedByTheSameActualLoop() = runBlocking {
        Prefs.initLocal(context); SettingsDataStore.init(context)
        val serviceController = Robolectric.buildService(AgentRuntimeService::class.java).create()
        try {
            val service = serviceController.get()
            val registry = AgentRuntimeService::class.java.getDeclaredField("runs").apply { isAccessible = true }.get(service) as RuntimeRunRegistry
            val handlerType = Class.forName("io.github.mangi.eta.agent.runtime.AgentRuntimeService\$IncomingHandler")
            val handler = handlerType.getDeclaredConstructor(AgentRuntimeService::class.java).apply { isAccessible = true }.newInstance(service) as Handler
            NexusExecutionStore(context).use { store ->
                val request = AgentRuntimeWire.RunRequest("voice-active", "Read the report", config, emptyList(),
                    handoff = AgentRuntimeWire.EntryHandoff("voice", AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                        AgentExternalArchivePayload("Read the report", "voice-chat", "Report").toJson()))
                val controller = AgentRunController()
                val recorder = NexusExecutionRecorder(store, NexusRunMapper.fromLegacy(request).request)
                recorder.bindController(controller); recorder.start(); recorder.prepareInput(emptyList(), request.prompt, 0)
                val events = mutableListOf<AgentEvent>()
                val session = AgentRuntimeSession(request.runId, controller, eventSink = { events += it }, eventRecorder = recorder::record,
                    terminalRecorder = { recorder.finish(it, controller.isUserCancelled) })
                val run = (registry.admit(request) { RuntimeRunRegistry.Run(request, session) } as RuntimeRunRegistry.Admission.Accepted).run
                run.prepared(request)
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                var calls = 0
                val provider = object : AgentProviderClient {
                    override val id = "supplement-fixture"
                    override val capabilities = OpenAiChatCompletionsProvider.capabilities
                    override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                        calls++; onEvent(ProviderEvent.RequestStarted)
                        if (calls == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                        else assertTrue(request.messages.toString().contains("Also check the totals"))
                        return ProviderResponse(JSONObject().put("role", "assistant").put("content", "report checked $calls").put("finish_reason", "stop"))
                    }
                }
                val task = async(Dispatchers.IO) { AgentModelClient.complete(config, request.prompt, AgentModelClient.ToolExecutor { error("No tools") },
                    provider = provider, runController = controller, onEvent = recorder::record,
                    onTranscript = { recorder.saveCheckpoint(it, null) }) }
                try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    var response: Boolean? = null
                    val reply = Messenger(object : Handler(Looper.getMainLooper()) {
                        override fun handleMessage(msg: Message) { response = msg.data.getBoolean("accepted") }
                    })
                    fun send(uid: Int, text: String) {
                        handler.handleMessage(Message.obtain().apply { what = AgentRuntimeWire.MSG_SUPPLEMENT; sendingUid = uid; replyTo = reply
                            data = AgentRuntimeWire.ackBundle(request.runId).apply { putString("supplement_text", text) } })
                        shadowOf(Looper.getMainLooper()).idle()
                    }
                    send(Process.myUid() + 99, "Forged cross-app request")
                    assertNull(response); assertFalse(controller.hasPendingSteering)
                    send(Process.myUid(), "Also check the totals")
                    assertEquals(true, response)
                    assertTrue(controller.hasPendingSteering)
                    assertEquals("Also check the totals", events.filterIsInstance<AgentEvent.UserSupplementReceived>().single().text)
                    val snapshot = AgentExternalArchivePayload.from(run.snapshotRequest().handoff!!.payload)!!
                    assertEquals("Also check the totals", snapshot.supplements.single().text)
                    release.countDown()
                    val result = withTimeout(5000) { task.await() }
                    assertEquals(2, calls)
                    assertEquals("report checked 2", result.content)
                    assertFalse(controller.hasPendingSteering)
                    assertTrue(store.checkpoint(NexusRunMapper.canonicalRunId(request.runId))!!.payload.contains("Also check the totals"))
                    assertFalse(store.timeline(NexusRunMapper.canonicalRunId(request.runId)).toString().contains("Also check the totals"))
                    session.complete(AgentRuntimeWire.RunResult(request.runId, true, result.content))
                    send(Process.myUid(), "Late message")
                    assertEquals(false, response)
                } finally { release.countDown(); controller.cancel(); task.cancelAndJoin() }
            }
        } finally { serviceController.destroy() }
    }

    @Test fun lateSpeechStateCannotOverwriteANewerCaptureAndFinalTextSurvivesUnconfirmedSubmission() {
        RunVoiceSupplement.begin("old")
        RunVoiceSupplement.begin("new")
        RunVoiceSupplement.publish("old", SpeechInputState(phase = EtaSpeechPhase.LISTENING, preview = "old speech"))
        assertEquals("new", RunVoiceSupplement.state.value.runId)
        RunVoiceSupplement.publish("new", SpeechInputState(phase = EtaSpeechPhase.RECOGNIZING, preview = "new speech"))
        RunVoiceSupplement.completed("old", "late")
        assertTrue(RunVoiceSupplement.state.value.active)
        RunVoiceSupplement.completed("new", "submission unconfirmed", "new speech")
        assertFalse(RunVoiceSupplement.state.value.active)
        assertEquals("new speech", RunVoiceSupplement.state.value.preview)
    }
}
