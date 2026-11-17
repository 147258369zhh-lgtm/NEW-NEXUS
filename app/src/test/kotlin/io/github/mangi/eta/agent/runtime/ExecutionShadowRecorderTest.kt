package io.github.mangi.eta.agent.runtime

import java.io.File
import kotlin.io.path.createTempDirectory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

        assertTrue(recorder.awaitClosed())
        val lines = file.readLines()
        assertEquals(4, lines.size)
        assertTrue(lines.first().contains("run.started"))
        assertTrue(lines.last().contains("run.terminal"))
        assertTrue(lines.last().contains("content_chars"))
        assertTrue(!file.readText().contains("secret output"))
    }

    @Test fun typedToolEventsShareAStableItemAndEventIdsAreUnique() {
        val file = File(createTempDirectory("nexus-ledger-tool-test").toFile(), "run.jsonl")
        val recorder = ExecutionShadowRecorder(
            output = file,
            metadata = JSONObject().put("run_id", "run-1").put("session_id", "conversation-1"),
        )
        recorder.record(AgentEvent.ToolStarted(2, "call-1", "read_file", "secret args"))
        recorder.record(AgentEvent.ToolFinished(2, "call-1", "read_file", "ok", 0, 0, success = true))
        recorder.finish(AgentRuntimeWire.RunResult("run-1", true, "done", ""))

        assertTrue(recorder.awaitClosed())
        val entries = file.readLines().map(::JSONObject)
        val started = entries.first { it.getString("type") == "tool.item.started" }
        val finished = entries.first { it.getString("type") == "tool.item.completed" }
        assertEquals(started.getString("item_id"), finished.getString("item_id"))
        assertEquals("turn-2", started.getString("turn_id"))
        assertEquals(entries.map { it.getString("event_id") }.toSet().size, entries.size)
        assertFalse(file.readText().contains("secret args"))
        assertNotNull(entries.firstOrNull { it.getString("type") == "run.terminal" })
    }

    @Test fun queueOverflowRetainsTerminalAndClosesTheWriter() {
        val opened = CountDownLatch(1)
        val release = CountDownLatch(1)
        val bytes = ByteArrayOutputStream()
        val file = File(createTempDirectory("nexus-ledger-overflow").toFile(), "run.jsonl")
        val recorder = ExecutionShadowRecorder(file, JSONObject().put("run_id", "overflow"), queueCapacity = 1, openOutput = {
            opened.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            bytes
        })
        try {
            assertTrue(opened.await(1, TimeUnit.SECONDS))
            repeat(10) { recorder.record(AgentEvent.RoundStarted(it + 1, 1)) }
            recorder.finish(AgentRuntimeWire.RunResult("overflow", false, "", "sensitive token in error"))
        } finally { release.countDown() }
        assertTrue(recorder.awaitClosed())
        assertTrue(recorder.health().droppedEvents > 0)
        val content = bytes.toString(Charsets.UTF_8.name())
        val lines = content.trim().lineSequence().map(::JSONObject).toList()
        assertEquals("run.terminal", lines.last().getString("type"))
        assertTrue(lines.last().getJSONObject("payload").getLong("dropped_total") > 0)
        assertFalse(content.contains("sensitive token"))
        val inspection = ExecutionLedgerInspector.inspect(content.trim().lineSequence())
        assertFalse(inspection.structurallyComplete)
        assertTrue("EVENTS_DROPPED" in inspection.issues)
    }

    @Test fun writerOpenFailureIsVisibleAndDoesNotCrashTheRun() {
        val file = File(createTempDirectory("nexus-ledger-open-failure").toFile(), "run.jsonl")
        val recorder = ExecutionShadowRecorder(file, JSONObject().put("run_id", "failed"), openOutput = { throw IOException("secret") })
        recorder.finish(AgentRuntimeWire.RunResult("failed", true, "done", ""))
        assertTrue(recorder.awaitClosed())
        assertEquals("IOException", recorder.health().failureType)
        assertEquals(-1L, recorder.health().committedSequence)
    }

    @Test fun concurrentEventsHaveStrictSequenceAndUniqueIds() {
        val file = File(createTempDirectory("nexus-ledger-concurrent").toFile(), "run.jsonl")
        val recorder = ExecutionShadowRecorder(file, JSONObject().put("run_id", "concurrent"))
        val workers = (1..4).map { worker -> kotlin.concurrent.thread {
            repeat(25) { recorder.record(AgentEvent.RoundStarted(worker * 25 + it, 1)) }
        } }
        workers.forEach { it.join(1_000); assertFalse(it.isAlive) }
        recorder.finish(AgentRuntimeWire.RunResult("concurrent", true, "done", ""))
        assertTrue(recorder.awaitClosed())
        val rows = file.readLines().map(::JSONObject)
        assertEquals((0L until rows.size.toLong()).toList(), rows.map { it.getLong("sequence") })
        assertEquals(102, rows.size)
        assertTrue(ExecutionLedgerInspector.inspect(file).structurallyComplete)
    }

    @Test fun missingResultsAndMalformedTailCannotPassIntegrityCheck() {
        val file = File(createTempDirectory("nexus-ledger-incomplete").toFile(), "run.jsonl")
        val recorder = ExecutionShadowRecorder(file, JSONObject().put("run_id", "incomplete"))
        recorder.record(AgentEvent.ToolStarted(1, "uncertain", "write_file", "private file"))
        recorder.close()
        assertTrue(recorder.awaitClosed())
        file.appendText("{broken tail")
        val report = ExecutionLedgerInspector.inspect(file)
        assertFalse(report.structurallyComplete)
        assertEquals(1, report.unresolvedTools)
        assertTrue("MALFORMED_EVENT" in report.issues)
        assertTrue("INCOMPLETE_OR_DUPLICATE_TERMINAL" in report.issues)
    }
}
