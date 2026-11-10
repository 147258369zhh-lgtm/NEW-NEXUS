package io.github.mangi.eta.agent.runtime

import java.io.File
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionShadowRecorderTest {
    @Test fun writesShadowEventsAndTerminalWithoutRawContent() {
        val file = File(createTempDirectory("nexus-ledger-test").toFile(), "run.jsonl")
        val recorder = ExecutionShadowRecorder(
            output = file,
            metadata = JSONObject().put("run_id", "run-1").put("session_id", "conversation-1"),
        )
        recorder.record(AgentEvent.RunStarted(0, 0, 3, false))
        recorder.record(AgentEvent.RunFailed("provider unavailable"))
        recorder.finish(AgentRuntimeWire.RunResult("run-1", false, "secret output", "provider unavailable"))

        val deadline = System.currentTimeMillis() + 2_000
        while ((!file.exists() || file.readLines().size < 4) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val lines = file.readLines()
        assertEquals(4, lines.size)
        assertTrue(lines.first().contains("run.started"))
        assertTrue(lines.last().contains("run.terminal"))
        assertTrue(lines.last().contains("content_chars"))
        assertTrue(!file.readText().contains("secret output"))
    }
}
