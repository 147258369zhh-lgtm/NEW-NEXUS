package io.github.mangi.eta.agent.capability

import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.ReplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class NexusToolEffectClassifierTest {
    @Test
    fun unknownToolsFailClosed() {
        assertEquals(EffectClass.HIGH_RISK, NexusToolEffectClassifier.classify("new_unclassified_tool"))
        assertEquals(ReplayPolicy.REQUIRE_USER, NexusToolEffectClassifier.replayPolicy(EffectClass.HIGH_RISK))
    }

    @Test
    fun terminalAndReadToolsHaveDistinctRecoveryPolicies() {
        assertEquals(EffectClass.HIGH_RISK, NexusToolEffectClassifier.classify("terminal"))
        assertEquals(EffectClass.READ, NexusToolEffectClassifier.classify("read_file"))
        assertEquals(ReplayPolicy.AUTOMATIC, NexusToolEffectClassifier.replayPolicy(EffectClass.READ))
    }
}
