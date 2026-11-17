package io.github.mangi.eta.agent.voice

import org.junit.Assert.assertThrows
import org.junit.Test

class LocalSpeechInputBudgetTest {
    @Test fun boundsAggregatePcmBeforeItReachesOnlineAndOfflineEngines() {
        val budget = LocalSpeechInputBudget(6)
        budget.accept(ByteArray(4)); budget.accept(ByteArray(2))
        assertThrows(IllegalArgumentException::class.java) { budget.accept(ByteArray(2)) }
    }

    @Test fun rejectsPartialSamplesWithoutConsumingTheRemainingBudget() {
        val budget = LocalSpeechInputBudget(4)
        assertThrows(IllegalArgumentException::class.java) { budget.accept(ByteArray(3)) }
        budget.accept(ByteArray(4))
    }
}
