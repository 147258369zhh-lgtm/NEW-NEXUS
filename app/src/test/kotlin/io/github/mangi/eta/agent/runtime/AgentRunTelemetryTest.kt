package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.*
import org.junit.Test

class AgentRunTelemetryTest {
    @Test fun firstTextIsCapturedOncePerAttemptWithoutSavingTheText() {
        var time = 0L
        val recorder = AgentRunTelemetry(request(), monotonicClock = { time })
        recorder.accept(AgentEvent.ProviderRequestStarted(1))
        time = 200
        repeat(3) { recorder.accept(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.TEXT, 0, 7, "PRIVATE")) }
        time = 300
        recorder.accept(AgentEvent.AssistantReceived(1, 21, "", emptyList()))
        val trace = recorder.finish(true).trace
        assertEquals(1, trace.count { it.kind == "first_text" })
        assertEquals(200L, trace.single { it.kind == "first_text" }.elapsedMs)
        assertEquals(300L, trace.single { it.kind == "assistant" }.elapsedMs)
        assertFalse(trace.toString().contains("PRIVATE"))
    }
    private fun request(source: String = "runtime", payload: String = "") = AgentRuntimeWire.RunRequest(
        runId = "metric-run", prompt = "PRIVATE_PROMPT", images = emptyList(),
        config = AgentModelClient.ModelConfig(baseUrl = "https://private.invalid", apiKey = "PRIVATE_KEY", model = "main", providerId = "provider", systemPrompt = "PRIVATE_SYSTEM_PROMPT"),
        handoff = AgentRuntimeWire.EntryHandoff("entry", source, payload),
    )

    @Test fun accessibilityWaitStagesHaveDurableTraceWithoutCountingNewModelOrToolCalls() {
        val telemetry = AgentRunTelemetry(request())
        val wait = AgentEvent.AccessibilityWaiting(1, io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase.WAITING)
        repeat(2) { telemetry.accept(wait) }
        telemetry.accept(wait.copy(phase = io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase.RESTORED))
        val snapshot = telemetry.finish(true)
        assertEquals(listOf("waiting", "restored"), snapshot.trace.filter { it.kind == "accessibility" }.map { it.label })
        assertNull(snapshot.metrics.inputTokens)
    }

    @Test fun replayAndCancellationDoNotRecountOrChangeTheTerminalSnapshot() {
        var clock = 100L
        val snapshots = mutableListOf<AgentRunTelemetrySnapshot>()
        val telemetry = AgentRunTelemetry(request(), wallClock = { 1000 + clock }, monotonicClock = { clock }, onSnapshot = snapshots::add)
        val session = AgentRuntimeSession("metric-run", telemetry = telemetry)
        session.emit(AgentEvent.RoundStarted(1, 1))
        session.emit(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 8, outputTokens = 2)))
        repeat(3) { assertTrue(session.attach({}, {})) }
        assertEquals(8L, telemetry.snapshot().metrics.inputTokens)
        clock = 600L
        assertTrue(session.cancel("PRIVATE_ERROR"))
        val terminal = telemetry.snapshot()
        assertEquals(AgentMetricStatus.CANCELLED, terminal.status)
        assertEquals(500L, terminal.durationMs)
        clock = 9900L
        assertFalse(session.emit(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 999))))
        assertFalse(session.complete(AgentRuntimeWire.RunResult("metric-run", true, "done")))
        assertEquals(terminal, telemetry.finish(true))
        assertEquals(1, snapshots.count { it.status == AgentMetricStatus.CANCELLED })
    }

    @Test fun structuredTraceIsBoundedAndNeverIncludesPayloadTextOrCredentials() {
        val telemetry = AgentRunTelemetry(request())
        telemetry.accept(AgentEvent.ToolStarted(1, "tool", "read_file", "PRIVATE_ARGUMENT", "PRIVATE_COMMAND"))
        telemetry.accept(AgentEvent.ToolFinished(1, "tool", "read_file", "PRIVATE_RESULT", 0, 0, true))
        telemetry.accept(AgentEvent.UserSupplementReceived(1, "PRIVATE_SUPPLEMENT"))
        telemetry.accept(AgentEvent.ToolStarted(1, "bad", "PRIVATE TOOL NAME WITH TEXT", ""))
        repeat(AgentRunTelemetry.MAX_TRACE_STEPS + 30) { telemetry.accept(AgentEvent.ProviderRequestStarted(2)) }
        val finished = telemetry.finish(true)
        assertEquals(AgentRunTelemetry.MAX_TRACE_STEPS, finished.trace.size)
        assertTrue(finished.omittedTraceSteps > 0)
        assertEquals(finished.trace.size.toLong() + finished.omittedTraceSteps, finished.trace.last().sequence + 1)
        assertFalse(finished.toString().contains("PRIVATE_"))
        assertEquals(1, finished.metrics.succeededTools)
        assertEquals(AgentMetricStatus.SUCCEEDED, finished.trace.last().kind)
    }

    @Test fun repeatedProgressAndUnchangedUsageDoNotFloodTraceOrQueueWrites() {
        var clock = 0L
        val snapshots = mutableListOf<AgentRunTelemetrySnapshot>()
        val telemetry = AgentRunTelemetry(request(), monotonicClock = { clock }, onSnapshot = snapshots::add)
        telemetry.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 3)))
        repeat(600) {
            telemetry.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 3)))
            telemetry.accept(AgentEvent.SubagentProgress(1, "call", "child", "explore", "PRIVATE_SNAPSHOT"))
        }
        assertEquals(1, telemetry.snapshot().trace.count { it.kind == "usage" })
        assertEquals(1, telemetry.snapshot().trace.count { it.kind == "subagent" })
        clock = 10000
        repeat(100) { telemetry.accept(AgentEvent.AssistantBlockDelta(1, AgentEvent.AssistantBlockKind.TEXT, 0, 1, "x")) }
        assertEquals(1, snapshots.size)
        telemetry.finish(true)
        assertEquals(2, snapshots.size)
    }

    @Test fun backgroundSourceIdentityAndFallbackAttemptArePreserved() {
        val meeting = AgentRunTelemetry(request("meeting", "room-id"))
        assertEquals("room-id", meeting.snapshot().roomId)
        val task = AgentRunTelemetry(request("scheduled_task", "task-id"))
        assertEquals("task-id", task.snapshot().taskId)
        val invalid = AgentRunTelemetry(request("meeting", "{\"private\":\"text\"}"))
        assertEquals("", invalid.snapshot().roomId)
        meeting.accept(AgentEvent.RoundStarted(1, 1))
        meeting.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 5)))
        meeting.modelAttempt(request().config.copy(model = "backup"))
        meeting.accept(AgentEvent.RoundStarted(2, 1))
        meeting.accept(AgentEvent.UsageReceived(2, AgentTokenUsage(inputTokens = 2)))
        assertEquals(listOf(5L, 2L), meeting.finish(true).models.map { it.metrics.inputTokens })
    }

    @Test fun diagnosticCallbackFailureCannotFailARealRun() {
        val telemetry = AgentRunTelemetry(request(), onSnapshot = { error("diagnostic failure") })
        val session = AgentRuntimeSession("metric-run", telemetry = telemetry)
        assertTrue(session.emit(AgentEvent.RoundStarted(1, 1)))
        assertTrue(session.complete(AgentRuntimeWire.RunResult("metric-run", true, "answer")))
        assertEquals(AgentMetricStatus.SUCCEEDED, telemetry.snapshot().status)
    }

    @Test fun pricesFreezeBeforeEachAttemptAndCannotRewriteHistoryOrRetryAMissingPrice() {
        var price: AgentTokenPrice? = AgentTokenPrice.create("USD", "2", "8", now = 1)
        var resolutions = 0
        val telemetry = AgentRunTelemetry(request(), wallClock = { 1000 }, priceResolver = { _, _ -> resolutions++; price })
        assertEquals(0, resolutions) // Construction can happen on the Service main thread.
        telemetry.modelAttempt(request().config)
        price = AgentTokenPrice.create("CNY", "9", "9")
        telemetry.accept(AgentEvent.RoundStarted(1, 1))
        assertEquals("USD", telemetry.snapshot().models.single().price?.currency)
        assertEquals(1000L, telemetry.snapshot().models.single().price?.capturedAt)
        assertEquals(1, resolutions)
        price = null
        telemetry.modelAttempt(request().config.copy(model = "backup"))
        price = AgentTokenPrice.create("USD", "1", "1")
        telemetry.accept(AgentEvent.RoundStarted(2, 1))
        val models = telemetry.finish(true).models
        assertTrue(models[1].priceCaptured)
        assertNull(models[1].price)
        assertEquals(2, resolutions)
    }

    @Test fun failedPriceLookupDoesNotStopModelExecutionOrPretendPriceWasFound() {
        val telemetry = AgentRunTelemetry(request(), priceResolver = { _, _ -> error("database unavailable") })
        telemetry.modelAttempt(request().config)
        telemetry.accept(AgentEvent.RoundStarted(1, 1))
        val finished = telemetry.finish(true)
        assertEquals(AgentMetricStatus.SUCCEEDED, finished.status)
        assertTrue(finished.models.single().priceCaptured)
        assertNull(finished.models.single().price)
    }
}
