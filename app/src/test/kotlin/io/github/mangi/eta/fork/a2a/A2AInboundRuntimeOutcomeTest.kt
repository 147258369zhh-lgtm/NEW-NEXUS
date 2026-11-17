package io.github.mangi.eta.fork.a2a

import android.app.Application
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class A2AInboundRuntimeOutcomeTest {
    private val access = A2AToolAccess.readOnly("peer-test")

    @Test
    fun `runtime input pause becomes generic INPUT_REQUIRED without exposing question or options`() {
        val outcome = A2AInboundRuntimeOutcome.map(
            pauseResult(
                AgentRuntimeWire.RunPause(
                    kind = AgentRuntimeWire.RunPause.KIND_INPUT_REQUIRED,
                    question = "Which report should I open?",
                    options = listOf("weekly", "monthly"),
                ),
            ),
            access,
        )
        val input = outcome as A2AInboundExecutionOutcome.InputRequired
        assertEquals("需要设备所有者提供输入。", input.prompt)
        assertFalse(input.prompt.contains("Which report should I open?"))
        assertFalse(input.prompt.contains("weekly"))
        assertFalse(input.prompt.contains("monthly"))
        assertFalse(input.prompt.contains("private transcript"))
    }

    @Test
    fun `runtime input pause never transmits PII or nonstandard secrets`() {
        val outcome = A2AInboundRuntimeOutcome.map(
            pauseResult(
                AgentRuntimeWire.RunPause(
                    kind = AgentRuntimeWire.RunPause.KIND_INPUT_REQUIRED,
                    question = "Rotate account alice@example.com using private phrase violet-kite-73019, SSN 123-45-6789?",
                    options = listOf(
                        "alice@example.com",
                        "violet-kite-73019",
                        "SSN 123-45-6789",
                        "prod",
                        "staging",
                    ),
                ),
            ),
            access,
        )
        val prompt = (outcome as A2AInboundExecutionOutcome.InputRequired).prompt
        assertEquals("需要设备所有者提供输入。", prompt)
        listOf("alice@example.com", "violet-kite-73019", "123-45-6789", "prod").forEach {
            assertFalse(prompt.contains(it))
        }
    }

    @Test
    fun `runtime authorization pause becomes AUTH_REQUIRED`() {
        val pause = AgentRuntimeWire.RunPause(
            kind = AgentRuntimeWire.RunPause.KIND_AUTH_REQUIRED,
            reason = "The operation needs approval from the device owner before it can run.",
            pendingCallId = "call-1",
            pendingTool = "write_file",
            pendingEffect = "REVERSIBLE_WRITE",
            pendingArgumentsDigest = "c".repeat(64),
            pendingExecuted = false,
            settledCalls = listOf("read_file|READ|" + "b".repeat(64)),
        )
        val outcome = A2AInboundRuntimeOutcome.map(
            pauseResult(pause),
            access,
        )
        val auth = outcome as A2AInboundExecutionOutcome.AuthRequired
        assertTrue(auth.reason.startsWith("Authorization required:"))
        assertTrue(auth.reason.contains("device owner"))
        assertTrue(auth.reason.contains("approval"))
        // The exact pending-call identity stays local for the approval ledger but is never
        // rendered into the remote status text.
        assertEquals(pause, auth.pause)
        assertFalse(auth.reason.contains("write_file"))
        assertFalse(auth.reason.contains("c".repeat(64)))
    }

    @Test
    fun `failed runtime result without a pause is never reported as completion`() {
        val failure = runCatching {
            A2AInboundRuntimeOutcome.map(
                AgentRuntimeWire.RunResult(
                    runId = "task",
                    ok = false,
                    content = "",
                    error = "model unavailable: C:\\Users\\owner\\private\\prompt.txt",
                ),
                access,
            )
        }.exceptionOrNull()
        assertTrue(failure is A2AProtocolException)
        // 对端只收到固定文案；本机错误正文可能包含路径、提示词或凭据片段。
        assertEquals(A2AInboundRuntimeOutcome.GENERIC_FAILURE_MESSAGE, failure?.message)
        assertFalse(failure?.message.orEmpty().contains("model unavailable"))
        assertFalse(failure?.message.orEmpty().contains("private"))
    }

    @Test
    fun `unrelated runtime failure code stays a failure`() {
        val failure = runCatching {
            A2AInboundRuntimeOutcome.map(
                AgentRuntimeWire.RunResult(
                    runId = "task",
                    ok = false,
                    content = "",
                    error = "当前会话仍有任务在运行",
                    failureCode = "CONVERSATION_BUSY",
                ),
                access,
            )
        }.exceptionOrNull()
        assertTrue(failure is A2AProtocolException)
    }

    @Test
    fun `compact result without pause detail keeps the wait state from the failure code`() {
        val outcome = A2AInboundRuntimeOutcome.map(
            AgentRuntimeWire.RunResult(
                runId = "task",
                ok = false,
                content = "",
                error = "需要补充输入",
                failureCode = "A2A_INPUT_REQUIRED",
            ),
            access,
        )
        val prompt = (outcome as A2AInboundExecutionOutcome.InputRequired).prompt
        assertEquals("需要设备所有者提供输入。", prompt)
    }

    @Test
    fun `completed runtime answer stays completed`() {
        val outcome = A2AInboundRuntimeOutcome.map(
            AgentRuntimeWire.RunResult(runId = "task", ok = true, content = "final answer"),
            access,
        )
        assertEquals(A2AInboundExecutionOutcome.Completed("final answer"), outcome)
    }

    private fun pauseResult(pause: AgentRuntimeWire.RunPause) = AgentRuntimeWire.RunResult(
        runId = "task",
        ok = false,
        content = "",
        error = "需要补充输入后才能继续本次任务",
        failureCode = "A2A_${pause.kind}",
        pause = pause,
    )
}
