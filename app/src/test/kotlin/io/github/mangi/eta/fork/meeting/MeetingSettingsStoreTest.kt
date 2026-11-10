package io.github.mangi.eta.fork.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MeetingSettingsStoreTest {
    @Test
    fun meetingSettingsClampUnsafeValuesAndExposeRuntimeDefaults() {
        val store = MeetingSettingsStore.get(RuntimeEnvironment.getApplication())
        try {
            store.update {
                it.copy(
                    autoDispatchWithoutMention = false,
                    maxParallelParticipants = 99,
                    autoFollowMessages = false,
                    summaryPreviewLines = 99,
                    meetingAutoCompactionEnabled = false,
                    requestTimeoutSeconds = 1,
                    fallbackToAllAgents = false,
                    autoSummarizeResults = false,
                )
            }

            assertFalse(store.current.autoDispatchWithoutMention)
            assertFalse(store.current.autoFollowMessages)
            assertFalse(store.current.meetingAutoCompactionEnabled)
            assertFalse(store.current.fallbackToAllAgents)
            assertFalse(store.current.autoSummarizeResults)
            assertEquals(8, store.current.maxParallelParticipants)
            assertEquals(1, store.current.summaryPreviewLines)
            assertEquals(60, store.current.requestTimeoutSeconds)
        } finally {
            store.update { MeetingSettings() }
        }
    }
}
