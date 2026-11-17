package io.github.mangi.eta.agent.model

import android.content.ComponentCallbacks2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalModelMemoryReliefTest {
    @Test fun releasingSpeechFirstKeepsTheTextModelWhenPressureHasCleared() = runBlocking {
        var low = true
        val order = mutableListOf<String>()
        val owner = LocalModelMemoryRelief({ level ->
            assertEquals(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, level)
            order += "speech"; low = false
        }, { order += "measure"; low }, { fail("Text must be preserved after speech release") })
        owner.relieve(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(listOf("speech", "measure"), order)
    }

    @Test fun criticalPressureReleasesTextLastOnlyIfMemoryIsStillLow() = runBlocking {
        val order = mutableListOf<String>()
        val owner = LocalModelMemoryRelief({ order += "speech" }, { order += "measure"; true }, { critical ->
            assertTrue(critical); order += "text"
        })
        owner.relieve(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(listOf("speech", "measure", "text"), order)
    }

    @Test fun lowerPriorityReleaseFailureCannotFallThroughToEvictThePrimaryModel() = runBlocking {
        val owner = LocalModelMemoryRelief({ error("fixture") }, { true }, { fail("Failed speech release must not evict primary") })
        assertTrue(runCatching { owner.relieve(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) }.isFailure)
    }
}
