package io.github.zhh_lgtm.nexus.application.api

import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import io.github.zhh_lgtm.nexus.kernel.api.ReplayPolicy
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayPolicyEngineTest {
    @Test
    fun readOnlyDescriptorResumesAutomatically() {
        val verdict = ReplayPolicyEngine.decide(
            descriptor(ReplayPolicy.AUTOMATIC, EffectClass.READ),
            verifierKnown = false,
        )
        assertEquals(ReplayDecision.RESUME_AUTOMATIC, verdict.decision)
        assertEquals("REPLAY_AUTOMATIC", verdict.code)
    }

    @Test
    fun reversibleWriteWithKnownVerifierVerifiesBeforeResuming() {
        val verdict = ReplayPolicyEngine.decide(
            descriptor(ReplayPolicy.VERIFY_THEN_RESUME, EffectClass.REVERSIBLE_WRITE, "get_clipboard"),
            verifierKnown = true,
        )
        assertEquals(ReplayDecision.VERIFY_THEN_RESUME, verdict.decision)
        assertEquals("get_clipboard", verdict.verifierId)
    }

    @Test
    fun reversibleWriteWithoutVerifierAsksTheUser() {
        val verdict = ReplayPolicyEngine.decide(
            descriptor(ReplayPolicy.VERIFY_THEN_RESUME, EffectClass.REVERSIBLE_WRITE),
            verifierKnown = false,
        )
        assertEquals(ReplayDecision.REQUIRE_USER, verdict.decision)
        assertEquals("REPLAY_VERIFIER_MISSING", verdict.code)
    }

    @Test
    fun unregisteredVerifierAsksTheUser() {
        val verdict = ReplayPolicyEngine.decide(
            descriptor(ReplayPolicy.VERIFY_THEN_RESUME, EffectClass.IDEMPOTENT_WRITE, "missing_tool"),
            verifierKnown = false,
        )
        assertEquals(ReplayDecision.REQUIRE_USER, verdict.decision)
        assertEquals("REPLAY_VERIFIER_UNKNOWN", verdict.code)
    }

    @Test
    fun declaredPolicyCannotDowngradeExternalSideEffects() {
        val verdict = ReplayPolicyEngine.decide(
            descriptor(ReplayPolicy.AUTOMATIC, EffectClass.EXTERNAL_SIDE_EFFECT),
            verifierKnown = true,
        )
        assertEquals(ReplayDecision.REQUIRE_USER, verdict.decision)
        assertEquals("REPLAY_REQUIRE_USER", verdict.code)
    }

    @Test
    fun destructiveEffectNeverReplaysEvenWhenDeclaredAutomatic() {
        val verdict = ReplayPolicyEngine.decide(
            descriptor(ReplayPolicy.AUTOMATIC, EffectClass.DESTRUCTIVE),
            verifierKnown = true,
        )
        assertEquals(ReplayDecision.NEVER, verdict.decision)
        assertEquals("REPLAY_NEVER", verdict.code)
    }

    @Test
    fun declaredNeverSurvivesAReadEffect() {
        val verdict = ReplayPolicyEngine.decide(
            descriptor(ReplayPolicy.NEVER, EffectClass.READ),
            verifierKnown = false,
        )
        assertEquals(ReplayDecision.NEVER, verdict.decision)
    }

    @Test
    fun declaredVerifyStaysStricterThanAReadEffect() {
        val verdict = ReplayPolicyEngine.decide(
            descriptor(ReplayPolicy.VERIFY_THEN_RESUME, EffectClass.READ, "get_clipboard"),
            verifierKnown = true,
        )
        assertEquals(ReplayDecision.VERIFY_THEN_RESUME, verdict.decision)
    }

    @Test
    fun highRiskAndGuiEffectsRequireTheUser() {
        assertEquals(
            ReplayDecision.REQUIRE_USER,
            ReplayPolicyEngine.decide(descriptor(ReplayPolicy.REQUIRE_USER, EffectClass.HIGH_RISK), verifierKnown = true).decision,
        )
        assertEquals(
            ReplayDecision.REQUIRE_USER,
            ReplayPolicyEngine.decide(descriptor(ReplayPolicy.REQUIRE_USER, EffectClass.GUI_ACTION), verifierKnown = true).decision,
        )
        assertEquals(
            ReplayDecision.REQUIRE_USER,
            ReplayPolicyEngine.decide(descriptor(ReplayPolicy.REQUIRE_USER, EffectClass.NON_IDEMPOTENT_WRITE), verifierKnown = true).decision,
        )
    }

    @Test
    fun unknownDescriptorFailsClosed() {
        val verdict = ReplayPolicyEngine.decide(descriptor = null, verifierKnown = false)
        assertEquals(ReplayDecision.REQUIRE_USER, verdict.decision)
        assertEquals("REPLAY_UNKNOWN_TOOL", verdict.code)
        assertNull(verdict.verifierId)
    }

    @Test
    fun unknownPolicyOrEffectFailsClosed() {
        val verdict = ReplayPolicyEngine.decide(
            policy = null,
            effect = null,
            verifierId = null,
            verifierKnown = true,
        )
        assertEquals(ReplayDecision.REQUIRE_USER, verdict.decision)
        assertEquals("REPLAY_UNKNOWN_DESCRIPTOR", verdict.code)
    }

    private fun descriptor(
        replayPolicy: ReplayPolicy,
        effectClass: EffectClass,
        verifierId: String? = null,
    ) = ToolDescriptor(
        id = "fixture_tool",
        namespace = "fixture",
        shortDescription = "Fixture tool",
        modelDescription = "Fixture tool used by the replay policy matrix",
        inputSchemaRef = "tool://fixture_tool/input",
        effectClass = effectClass,
        replayPolicy = replayPolicy,
        timeoutMs = 30_000,
        verifierId = verifierId,
        hostKind = HostKind.NATIVE_ANDROID,
    )
}
