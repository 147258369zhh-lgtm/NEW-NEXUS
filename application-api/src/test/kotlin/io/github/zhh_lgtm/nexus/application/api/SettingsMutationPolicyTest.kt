package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.SettingRisk
import io.github.zhh_lgtm.nexus.kernel.api.SettingsDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMutationPolicyTest {
    private val descriptor = SettingsDescriptor(
        id = "security.root",
        title = "Root access",
        valueType = "boolean",
        scope = "global",
        risk = SettingRisk.SECURITY,
        sensitive = false,
        requiresRemoteApproval = true,
        undoable = true,
    )

    @Test
    fun remoteAgentCanOnlyRequestAndSecurityNeedsLocalConfirmation() {
        assertEquals(
            SettingsApplyDecision.RemoteRequestOnly,
            SettingsMutationPolicy.decide(descriptor, SettingsActorKind.REMOTE_AGENT, locallyConfirmed = true),
        )
        assertEquals(
            SettingsApplyDecision.RequiresLocalConfirmation,
            SettingsMutationPolicy.decide(descriptor, SettingsActorKind.LOCAL_AGENT, locallyConfirmed = false),
        )
        assertEquals(
            SettingsApplyDecision.Apply,
            SettingsMutationPolicy.decide(descriptor, SettingsActorKind.LOCAL_AGENT, locallyConfirmed = true),
        )
    }
}
