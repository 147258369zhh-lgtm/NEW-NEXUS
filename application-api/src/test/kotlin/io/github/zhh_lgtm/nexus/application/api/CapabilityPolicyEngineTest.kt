package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.CapabilitySnapshot
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.HostId
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import io.github.zhh_lgtm.nexus.kernel.api.ReplayPolicy
import io.github.zhh_lgtm.nexus.kernel.api.TaskId
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityPolicyEngineTest {
    private val descriptor = ToolDescriptor(
        id = "terminal",
        namespace = "device",
        shortDescription = "Run a command",
        modelDescription = "Run a command in the selected host",
        inputSchemaRef = "tool://terminal/input",
        requiredCapabilities = setOf("terminal.run"),
        effectClass = EffectClass.HIGH_RISK,
        replayPolicy = ReplayPolicy.REQUIRE_USER,
        timeoutMs = 30_000,
        hostKind = HostKind.NATIVE_ANDROID,
    )

    @Test
    fun highRiskToolNeedsLocalApprovalAndUsableCapability() {
        val capability = CapabilitySnapshot(
            hostId = HostId("native"),
            capability = "terminal.run",
            available = true,
            granted = true,
            authorized = true,
        )
        val grant = ToolGrant(
            taskId = TaskId("task-1"),
            hostId = HostId("native"),
            hostKind = HostKind.NATIVE_ANDROID,
            allowedTools = setOf("terminal"),
            allowedEffects = setOf(EffectClass.HIGH_RISK),
            highRiskApproved = false,
            validUntil = 2_000,
        )
        assertEquals(
            CapabilityAuthorization.Denied("LOCAL_CONFIRMATION_REQUIRED", "High-risk tools require local confirmation"),
            CapabilityPolicyEngine.authorize(descriptor, grant, listOf(capability), now = 1_000),
        )
        assertEquals(
            CapabilityAuthorization.Allowed,
            CapabilityPolicyEngine.authorize(descriptor, grant.copy(highRiskApproved = true), listOf(capability), now = 1_000),
        )
    }

    @Test
    fun directoryListsSummaryBeforeActivatingSchema() {
        val directory = InMemoryCapabilityDirectory(listOf(descriptor.copy(effectClass = EffectClass.READ)))
        val summary = directory.list("term").single()
        assertEquals("terminal", summary.id)
        assertTrue(!summary.active)
        directory.activate("terminal", expiresAt = 2_000)
        assertTrue(directory.list("terminal").single().active)
        assertEquals("tool://terminal/input", directory.activate("terminal", 3_000).schemaRef)
    }
}
