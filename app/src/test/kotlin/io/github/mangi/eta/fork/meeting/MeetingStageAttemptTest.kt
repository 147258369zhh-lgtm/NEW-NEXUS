package io.github.mangi.eta.fork.meeting

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MeetingStageAttemptTest {
    @Test fun failedParticipantDoesNotDiscardOtherResults() = runBlocking {
        val results = listOf(
            async { meetingStageAttempt({ "timeout" }, { "failed" }) { throw IllegalStateException() } },
            async { meetingStageAttempt({ "timeout" }, { "failed" }) { "delivered" } },
        ).awaitAll()
        assertEquals(listOf("failed", "delivered"), results)
    }

    @Test fun cancellationIsNotConvertedIntoFailure() = runBlocking {
        try {
            meetingStageAttempt({ "timeout" }, { "failed" }) { throw CancellationException("stop") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }
}
