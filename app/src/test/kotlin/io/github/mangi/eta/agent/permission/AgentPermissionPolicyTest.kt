package io.github.mangi.eta.agent.permission

import io.github.mangi.eta.data.model.AgentPermissionMode
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPermissionPolicyTest {
    @Test
    fun fullAccessHasNoAppExecutionApprovalExceptionIncludingFinancialAndRoot() {
        assertFalse(AgentPermissionPolicy.requiresManualApproval(
            AgentPermissionMode.FULL_ACCESS, EffectClass.HIGH_RISK, "delete_file", JSONObject(),
        ))
        assertFalse(AgentPermissionPolicy.requiresManualApproval(
            AgentPermissionMode.FULL_ACCESS, EffectClass.EXTERNAL_SIDE_EFFECT, "checkout", JSONObject(),
        ))
        assertFalse(AgentPermissionPolicy.requiresManualApproval(
            AgentPermissionMode.FULL_ACCESS, EffectClass.HIGH_RISK, "terminal", JSONObject().put("identity", "root"),
        ))
        assertFalse(AgentPermissionPolicy.requiresManualApproval(
            AgentPermissionMode.FULL_ACCESS, EffectClass.HIGH_RISK, "terminal", JSONObject(),
            protectedRootRequest = true,
        ))
        assertFalse(AgentPermissionPolicy.requiresManualApproval(
            AgentPermissionMode.FULL_ACCESS, EffectClass.DESTRUCTIVE, "update_security_policy", JSONObject(),
        ))
    }

    @Test
    fun smartModeAutomatesMediumRiskWorkButPromptsForHighRiskDestructiveOrExternalWork() {
        assertFalse(AgentPermissionPolicy.requiresManualApproval(
            AgentPermissionMode.SMART_APPROVAL, EffectClass.IDEMPOTENT_WRITE, "write_file", JSONObject(),
        ))
        assertFalse(AgentPermissionPolicy.requiresManualApproval(
            AgentPermissionMode.SMART_APPROVAL, EffectClass.GUI_ACTION, "tap", JSONObject(),
        ))
        assertTrue(AgentPermissionPolicy.requiresManualApproval(
            AgentPermissionMode.SMART_APPROVAL, EffectClass.HIGH_RISK, "terminal", JSONObject().put("identity", "root"),
        ))
        assertTrue(AgentPermissionPolicy.requiresManualApproval(
            AgentPermissionMode.SMART_APPROVAL, EffectClass.EXTERNAL_SIDE_EFFECT, "publish", JSONObject(),
        ))
        assertFalse(AgentPermissionPolicy.requiresManualApproval(
            AgentPermissionMode.SMART_APPROVAL, EffectClass.NON_IDEMPOTENT_WRITE, "move_file", JSONObject(),
        ))
    }

    @Test
    fun manualModeStillAutomatesLowAndMediumRiskMutations() {
        assertFalse(AgentPermissionPolicy.requiresManualApproval(
            AgentPermissionMode.MANUAL_APPROVAL, EffectClass.READ, "read_file", JSONObject(),
        ))
        listOf(EffectClass.REVERSIBLE_WRITE, EffectClass.IDEMPOTENT_WRITE, EffectClass.GUI_ACTION,
            EffectClass.NON_IDEMPOTENT_WRITE)
            .forEach { effect ->
                assertFalse(AgentPermissionPolicy.requiresManualApproval(
                    AgentPermissionMode.MANUAL_APPROVAL, effect, "operation", JSONObject(),
                ))
            }
        listOf(EffectClass.DESTRUCTIVE, EffectClass.EXTERNAL_SIDE_EFFECT, EffectClass.HIGH_RISK).forEach { effect ->
            assertTrue(AgentPermissionPolicy.requiresManualApproval(
                AgentPermissionMode.MANUAL_APPROVAL, effect, "operation", JSONObject(),
            ))
        }
    }

    @Test
    fun financialAppControlFollowsTheSelectedApprovalMode() {
        AgentPermissionMode.entries.forEach { mode ->
            assertEquals(mode != AgentPermissionMode.FULL_ACCESS, AgentPermissionPolicy.requiresManualAppControl(
                mode = mode,
                isWrite = true,
                isConfirm = false,
                capabilityId = "settings.budget",
                arguments = JSONObject().put("cost", 10),
            ))
        }
    }

    @Test
    fun nonFinancialSecurityChangesFollowTheSelectedApprovalTier() {
        assertFalse(AgentPermissionPolicy.requiresManualAppControl(
            mode = AgentPermissionMode.FULL_ACCESS, isWrite = true, isConfirm = true,
            capabilityId = "settings.agent_permission", arguments = JSONObject().put("mode", "full_access"),
        ))
        assertTrue(AgentPermissionPolicy.requiresManualAppControl(
            mode = AgentPermissionMode.SMART_APPROVAL, isWrite = true, isConfirm = true,
            capabilityId = "settings.agent_permission", arguments = JSONObject().put("mode", "full_access"),
        ))
        assertFalse(AgentPermissionPolicy.requiresManualApproval(
            AgentPermissionMode.FULL_ACCESS, EffectClass.HIGH_RISK, "update_security_policy", JSONObject(),
        ))
    }

    @Test
    fun rootIdentityReversibleWritesCopiesAndMovesNeedConfirmationInSmartAndManualModes() {
        val rootWrites = listOf("write_file", "copy_file", "move_file")
        listOf(AgentPermissionMode.SMART_APPROVAL, AgentPermissionMode.MANUAL_APPROVAL).forEach { mode ->
            rootWrites.forEach { tool ->
                val arguments = JSONObject().put("path", "/data/local/tmp/fixture.txt")
                assertTrue("$mode $tool as root", AgentPermissionPolicy.requiresManualApproval(
                    mode, EffectClass.REVERSIBLE_WRITE, tool, arguments, protectedRootRequest = true,
                ))
                assertFalse("$mode $tool as user", AgentPermissionPolicy.requiresManualApproval(
                    mode, EffectClass.REVERSIBLE_WRITE, tool, arguments, protectedRootRequest = false,
                ))
            }
        }
    }

    @Test
    fun fullAccessAndRootReadsKeepTheEstablishedApprovalBoundary() {
        AgentPermissionMode.entries.forEach { mode ->
            assertFalse(AgentPermissionPolicy.requiresManualApproval(
                mode, EffectClass.READ, "read_file", JSONObject(), protectedRootRequest = true,
            ))
        }
        listOf(EffectClass.REVERSIBLE_WRITE, EffectClass.NON_IDEMPOTENT_WRITE, EffectClass.DESTRUCTIVE,
            EffectClass.HIGH_RISK)
            .forEach { effect ->
                assertFalse(AgentPermissionPolicy.requiresManualApproval(
                    AgentPermissionMode.FULL_ACCESS, effect, "write_file", JSONObject(), protectedRootRequest = true,
                ))
            }
    }

    @Test
    fun financialToolCallsRespectFullAccessWithoutChangingTheOtherApprovalModes() {
        AgentPermissionMode.entries.forEach { mode ->
            assertEquals(mode != AgentPermissionMode.FULL_ACCESS, AgentPermissionPolicy.requiresManualApproval(
                mode, EffectClass.REVERSIBLE_WRITE, "payment_checkout", JSONObject(), protectedRootRequest = true,
            ))
            assertEquals(mode != AgentPermissionMode.FULL_ACCESS, AgentPermissionPolicy.requiresManualApproval(
                mode, EffectClass.IDEMPOTENT_WRITE, "file_manage", JSONObject().put("note", "转账 100"),
            ))
        }
    }

    @Test fun confirmOnlyCapabilitiesCannotSkipSmartOrManualApproval() {
        listOf(AgentPermissionMode.SMART_APPROVAL, AgentPermissionMode.MANUAL_APPROVAL).forEach { mode ->
            assertTrue(AgentPermissionPolicy.requiresManualAppControl(mode, isWrite = false, isConfirm = true,
                capabilityId = "settings.memory.clear", arguments = JSONObject()))
        }
    }
}
