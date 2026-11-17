package io.github.mangi.eta.agent.permission

import io.github.mangi.eta.agent.approval.RunPolicySnapshot
import io.github.mangi.eta.agent.runtime.AgentToolPolicy
import io.github.mangi.eta.data.model.AgentPermissionMode
import io.github.mangi.eta.data.model.AgentPermissionState
import org.junit.Assert.*
import org.junit.Test

class RunPolicySnapshotTest {
    @Test fun aGlobalRelaxationCannotWidenAnExistingRun() {
        val old = snapshot(AgentPermissionMode.MANUAL_APPROVAL, 4)
        assertEquals(AgentPermissionMode.MANUAL_APPROVAL,
            old.effectiveMode(AgentPermissionState(AgentPermissionMode.FULL_ACCESS, 5, 0, 4)))
        val fresh = snapshot(AgentPermissionMode.FULL_ACCESS, 5)
        assertEquals(AgentPermissionMode.FULL_ACCESS,
            fresh.effectiveMode(AgentPermissionState(AgentPermissionMode.FULL_ACCESS, 5, 0, 4)))
    }

    @Test fun restrictThenRelaxBetweenCallsStillRestrictsTheOriginalRun() {
        val old = snapshot(AgentPermissionMode.FULL_ACCESS, 1)
        assertEquals(AgentPermissionMode.MANUAL_APPROVAL,
            old.effectiveMode(AgentPermissionState(AgentPermissionMode.FULL_ACCESS, 3, 0, 2)))
    }

    @Test fun removedToolsCannotReturnAfterGlobalRelaxation() {
        val old = snapshot(AgentPermissionMode.FULL_ACCESS, 1)
        old.restrictTools(AgentToolPolicy(disallowedTools = listOf("terminal*")))
        old.restrictTools(AgentToolPolicy.UNRESTRICTED)
        assertFalse(old.toolPolicy.isAllowed("terminal"))
        assertTrue(old.toolPolicy.isAllowed("read_file"))
    }

    private fun snapshot(mode: AgentPermissionMode, revision: Long) =
        RunPolicySnapshot("run", 1, revision, mode, AgentToolPolicy.UNRESTRICTED)
}
