package io.github.mangi.eta.fork.meeting

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MeetingRunCoordinatorQueueTest {
    @Test fun followUpIsAcceptedOnlyForAnActiveRoomAndIsBounded() {
        val context: Context = RuntimeEnvironment.getApplication()
        assertFalse(MeetingRunCoordinator.enqueue(context, "missing", "ignored"))
        assertEquals(0, MeetingRunCoordinator.queuedCount("missing"))
    }

    @Test fun blankFollowUpAndAttachmentlessBlankStartAreRejected() {
        val context: Context = RuntimeEnvironment.getApplication()
        assertFalse(MeetingRunCoordinator.start(context, "", ""))
        assertFalse(MeetingRunCoordinator.enqueue(context, "", ""))
    }
}
