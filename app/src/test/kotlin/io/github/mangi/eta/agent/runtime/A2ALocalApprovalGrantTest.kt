package io.github.mangi.eta.agent.runtime

import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.HostId
import io.github.zhh_lgtm.nexus.kernel.api.RunId
import io.github.zhh_lgtm.nexus.kernel.api.RunMode
import io.github.zhh_lgtm.nexus.kernel.api.RunRequest
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.control.EtaControlCapabilityInfo
import io.github.mangi.eta.agent.control.EtaControlDanger
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A2ALocalApprovalGrantTest {
    @Test
    fun exactToolAndEffectAreConsumedOnce() {
        val controller = controller(RunSource.A2A_INBOUND)
        controller.bindLocalA2AApproval(grant(), expectedTaskId = TASK_ID, expectedContinuationCount = 2)

        val first = controller.beginApproval("write_file", EffectClass.REVERSIBLE_WRITE)
        assertTrue(first.isPreapproved)

        val second = runCatching {
            controller.beginApproval("write_file", EffectClass.REVERSIBLE_WRITE)
        }.exceptionOrNull()
        assertTrue(second is AgentRunPauseException)
    }

    @Test
    fun mismatchedTaskCountEffectAndFingerprintsFailClosed() {
        val cases = listOf(
            Case(grant(), "other-task", 2, "write_file", EffectClass.REVERSIBLE_WRITE),
            Case(grant(), TASK_ID, 1, "write_file", EffectClass.REVERSIBLE_WRITE),
            Case(grant(), TASK_ID, 2, "write_file", EffectClass.GUI_ACTION),
            Case(grant(currentFingerprint = ""), TASK_ID, 2, "write_file", EffectClass.REVERSIBLE_WRITE),
            Case(
                grant(allowedEffects = setOf("write_file=REVERSIBLE_WRITE", "terminal=HIGH_RISK")),
                TASK_ID,
                2,
                "terminal",
                EffectClass.HIGH_RISK,
            ),
        )
        cases.forEach { case ->
            val controller = controller(RunSource.A2A_INBOUND)
            controller.bindLocalA2AApproval(
                case.grant,
                expectedTaskId = case.taskId,
                expectedContinuationCount = case.count,
            )
            val failure = runCatching {
                controller.beginApproval(case.tool, case.effect)
            }.exceptionOrNull()
            assertTrue(failure is AgentRunPauseException)
            assertFalse(controller.waitingForApproval)
        }
    }

    @Test
    fun nonA2ARunCannotUseAnInboundGrant() {
        val controller = controller(RunSource.CHAT)
        controller.bindLocalA2AApproval(grant(), expectedTaskId = TASK_ID, expectedContinuationCount = 2)

        val wait = controller.beginApproval("write_file", EffectClass.REVERSIBLE_WRITE)

        assertFalse(wait.isPreapproved)
        assertTrue(controller.waitingForApproval)
        wait.resolve(RunApprovalPhase.REJECTED)
    }

    @Test
    fun confirmationCapabilityIdIsNormalizedToTheGrantedTool() = runBlocking {
        val controller = controller(RunSource.A2A_INBOUND)
        controller.bindLocalA2AApproval(grant(), expectedTaskId = TASK_ID, expectedContinuationCount = 2)

        assertTrue(
            EtaAppControlConfirmation.awaitApproval(
                info = confirmation("runtime_tool:write_file"),
                parameters = JSONObject(),
                effect = EffectClass.REVERSIBLE_WRITE,
                controller = controller,
            ),
        )
        assertNull(EtaAppControlConfirmation.pending.value)
        assertFalse(controller.waitingForApproval)

        val outsideGrant = runCatching {
            EtaAppControlConfirmation.awaitApproval(
                info = confirmation("runtime_tool:edit_file"),
                parameters = JSONObject(),
                effect = EffectClass.REVERSIBLE_WRITE,
                controller = controller,
            )
        }.exceptionOrNull()
        assertTrue(outsideGrant is AgentRunPauseException)
        assertNull(EtaAppControlConfirmation.pending.value)
    }

    private fun confirmation(capabilityId: String) = EtaControlCapabilityInfo(
        id = capabilityId,
        title = "confirmation fixture",
        category = "agent_runtime",
        summary = "confirmation fixture",
        danger = EtaControlDanger.CONFIRM,
        parameters = JSONObject(),
        canRead = false,
    )

    private fun controller(source: RunSource): AgentRunController = AgentRunController().apply {
        bindRecoverySurface(source)
        bindExecutionIdentity(
            RunRequest(RunId("canonical-run"), source, RunMode.A2A, HostId("test")),
        )
    }

    private fun grant(
        currentFingerprint: String = "current",
        allowedEffects: Set<String> = setOf(
            "write_file=REVERSIBLE_WRITE",
            "request_user_input=READ",
        ),
    ) = AgentRuntimeWire.A2ALocalApprovalGrant(
        taskId = TASK_ID,
        messageId = "continuation-message",
        continuationCount = 2,
        allowedTools = setOf("write_file", "request_user_input"),
        allowedToolEffects = allowedEffects,
        originalGrantFingerprint = "original",
        currentGrantFingerprint = currentFingerprint,
    )

    private companion object {
        const val TASK_ID = "peer-task"
    }

    private data class Case(
        val grant: AgentRuntimeWire.A2ALocalApprovalGrant,
        val taskId: String,
        val count: Int,
        val tool: String,
        val effect: EffectClass,
    )
}
