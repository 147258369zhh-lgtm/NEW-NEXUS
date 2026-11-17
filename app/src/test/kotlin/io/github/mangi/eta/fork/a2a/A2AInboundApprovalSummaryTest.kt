package io.github.mangi.eta.fork.a2a

import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class A2AInboundApprovalSummaryTest {
    private val readDigest = "b".repeat(64)
    private val pendingDigest = "c".repeat(64)

    @Test
    fun `read only settled calls can be resumed from the exact pending call`() {
        val summary = requireNotNull(
            A2AInboundApprovalSummary.fromPause(
                slot = "slot-1",
                pause = pause(settledCalls = listOf("read_file|READ|$readDigest")),
            ),
        )
        assertEquals("slot-1", summary.slot)
        assertEquals("write_file", summary.tool)
        assertEquals("REVERSIBLE_WRITE", summary.effect)
        assertEquals(pendingDigest, summary.argumentsDigest)
        assertEquals(listOf("read_file|READ|$readDigest"), summary.settledCalls)
        assertFalse(summary.effectfulSettled)

        val stored = summary.encoded()
        assertTrue(stored.chars <= A2AInboundApprovalSummary.MAX_CHARS)
        assertEquals(
            summary,
            A2AInboundApprovalSummary.decode(stored.json, stored.digest, stored.chars, "slot-1"),
        )
        assertNull(
            A2AInboundApprovalSummary.decode(stored.json, stored.digest, stored.chars, "other-slot"),
        )
        // Tool and digest are ledger metadata; no argument value or transcript is stored.
        assertFalse(stored.json.contains("report.txt"))
    }

    @Test
    fun `effectful settled calls and in flight calls are never resumable`() {
        assertNull(
            A2AInboundApprovalSummary.fromPause(
                slot = "slot-1",
                pause = pause(settledCalls = listOf("write_file|REVERSIBLE_WRITE|$readDigest")),
            ),
        )
        assertNull(
            A2AInboundApprovalSummary.fromPause(
                slot = "slot-1",
                pause = pause(settledEffectful = true),
            ),
        )
        assertNull(
            A2AInboundApprovalSummary.fromPause(
                slot = "slot-1",
                pause = pause().copy(pendingExecuted = true),
            ),
        )
        assertNull(
            A2AInboundApprovalSummary.fromPause(
                slot = "slot-1",
                pause = pause().copy(pendingArgumentsDigest = "not-a-digest"),
            ),
        )
        assertNull(
            A2AInboundApprovalSummary.fromPause(
                slot = "slot-1",
                pause = pause().copy(pendingTool = ""),
            ),
        )
    }

    @Test
    fun `missing damaged truncated and oversized ledgers are rejected`() {
        val stored = requireNotNull(A2AInboundApprovalSummary.fromPause("slot-1", pause())).encoded()
        assertNull(A2AInboundApprovalSummary.decode(null, stored.digest, stored.chars, "slot-1"))
        assertNull(A2AInboundApprovalSummary.decode(stored.json, "0".repeat(64), stored.chars, "slot-1"))
        assertNull(A2AInboundApprovalSummary.decode(stored.json, stored.digest, stored.chars - 1, "slot-1"))
        assertNull(A2AInboundApprovalSummary.decode(stored.json.take(stored.json.length - 1), stored.digest, stored.chars, "slot-1"))

        val oversized = "x".repeat(A2AInboundApprovalSummary.MAX_CHARS + 1)
        assertNull(
            A2AInboundApprovalSummary.decode(
                oversized,
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(oversized.toByteArray())
                    .joinToString("") { "%02x".format(it) },
                oversized.length,
                "slot-1",
            ),
        )
    }

    @Test
    fun `settled keys require a known effect and a full digest`() {
        assertTrue(A2AInboundApprovalSummary.isSettledKey("read_file|READ|$readDigest"))
        assertFalse(A2AInboundApprovalSummary.isSettledKey("read_file|UNKNOWN|$readDigest"))
        assertFalse(A2AInboundApprovalSummary.isSettledKey("read_file|READ|short"))
        assertFalse(A2AInboundApprovalSummary.isSettledKey("read_file|READ"))
    }

    private fun pause(
        settledCalls: List<String> = emptyList(),
        settledEffectful: Boolean = false,
    ) = AgentRuntimeWire.RunPause(
        kind = AgentRuntimeWire.RunPause.KIND_AUTH_REQUIRED,
        reason = "The operation needs approval from the device owner before it can run.",
        pendingCallId = "call-1",
        pendingTool = "write_file",
        pendingEffect = "REVERSIBLE_WRITE",
        pendingArgumentsDigest = pendingDigest,
        pendingExecuted = false,
        settledCalls = settledCalls,
        settledEffectful = settledEffectful,
    )
}
