package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.Context
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RunUserInputA2APauseTest {

    @Test
    fun `inbound A2A run pauses request_user_input instead of publishing a local prompt`() {
        val controller = AgentRunController().apply { bindRecoverySurface(RunSource.A2A_INBOUND) }
        val arguments = JSONObject()
            .put("question", "Which file should I read?")
            .put("options", JSONArray(listOf("notes.md", "report.md")))

        val failure = runCatching {
            RuntimeInvocationScope.withController(controller) {
                RunUserInput.execute(context(), arguments)
            }
        }.exceptionOrNull()

        assertTrue(failure is AgentRunPauseException)
        val pause = requireNotNull((failure as AgentRunPauseException)).pause
        assertEquals(AgentRuntimeWire.RunPause.KIND_INPUT_REQUIRED, pause.kind)
        assertEquals("Which file should I read?", pause.question)
        assertEquals(listOf("notes.md", "report.md"), pause.options)
        assertTrue(RunUserInput.pending.value.isEmpty())
        assertFalse(controller.waitingForUser)
        assertSame(failure, controller.abortFailure)
        assertTrue(controller.isCancelled)
    }

    @Test
    fun `inbound A2A run turns a local approval wait into an authorization pause`() {
        val controller = AgentRunController().apply { bindRecoverySurface(RunSource.A2A_INBOUND) }

        val failure = runCatching { controller.beginApproval("terminal") }.exceptionOrNull()

        assertTrue(failure is AgentRunPauseException)
        assertEquals(
            AgentRuntimeWire.RunPause.KIND_AUTH_REQUIRED,
            (failure as AgentRunPauseException).pause.kind,
        )
        assertFalse(controller.waitingForApproval)
    }

    @Test
    fun `a resumed A2A turn still asks for authorization instead of treating input as approval`() {
        val controller = AgentRunController().apply { bindRecoverySurface(RunSource.A2A_INBOUND) }

        repeat(2) {
            val failure = runCatching { controller.beginApproval("write_file") }.exceptionOrNull()
            assertTrue(failure is AgentRunPauseException)
            assertFalse(controller.waitingForApproval)
        }
    }

    @Test
    fun `chat runs still wait for local approval`() {
        val controller = AgentRunController().apply { bindRecoverySurface(RunSource.CHAT) }

        val wait = controller.beginApproval("terminal")

        assertTrue(controller.waitingForApproval)
        wait.resolve(RunApprovalPhase.APPROVED)
        assertFalse(controller.waitingForApproval)
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()
}
