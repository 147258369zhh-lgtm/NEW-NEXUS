package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 启动恢复读取 checkpoint 时，未知工具结果必须按 descriptor 的 ReplayPolicy 分类。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentRunCheckpointReplayPolicyTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun restoredCheckpointClassifiesEachUnknownToolByReplayPolicy() {
        val runId = "run-replay-policy"
        assertTrue(AgentRunCheckpointStore.start(context, request(runId), ownerInstanceId = "old-process"))
        AgentRunCheckpointStore.saveTranscript(
            context,
            runId,
            listOf(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = "",
                    toolCallsJson = """[
                        {"id":"read","type":"function","function":{"name":"read_file","arguments":"{}"}},
                        {"id":"write","type":"function","function":{"name":"set_clipboard","arguments":"{}"}},
                        {"id":"risk","type":"function","function":{"name":"write_file","arguments":"{}"}},
                        {"id":"hostile","type":"function","function":{"name":"not_a_registered_tool","arguments":"{}"}},
                        {"id":"done","type":"function","function":{"name":"read_file","arguments":"{}"}}]
                    """,
                ),
                AgentModelClient.ConversationMessage("tool", "真实完成结果", toolCallId = "done"),
            ),
        )

        val restored = AgentRunCheckpointStore.list(context).single().transcript

        assertEquals("真实完成结果", restored.single { it.toolCallId == "done" }.content)
        assertFalse(JSONObject(restored.single { it.toolCallId == "read" }.content).getBoolean("ok"))
        assertFalse(JSONObject(restored.single { it.toolCallId == "write" }.content).getBoolean("ok"))
        assertEquals("TOOL_INTERRUPTED_REPLAY_SAFE", restored.codeOf("read"))
        assertEquals("TOOL_INTERRUPTED_VERIFY_FIRST", restored.codeOf("write"))
        assertEquals("get_clipboard", JSONObject(restored.single { it.toolCallId == "write" }.content).getString("verifier"))
        assertEquals("TOOL_INTERRUPTED_USER_CONFIRMATION", restored.codeOf("risk"))
        assertFalse(JSONObject(restored.single { it.toolCallId == "risk" }.content).getBoolean("ok"))
        assertEquals("TOOL_INTERRUPTED_UNKNOWN_TOOL", restored.codeOf("hostile"))
        assertFalse(JSONObject(restored.single { it.toolCallId == "hostile" }.content).getBoolean("ok"))
    }

    private fun List<AgentModelClient.ConversationMessage>.codeOf(toolCallId: String): String =
        JSONObject(single { it.toolCallId == toolCallId }.content).getString("code")

    private fun request(runId: String): AgentRuntimeWire.RunRequest =
        AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = "恢复测试",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.com/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
            ),
            images = emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                payload = "conversation-1",
            ),
        )
}
