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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import io.github.mangi.eta.data.repository.ConversationAttachments

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MeetingRunCoordinatorQueueTest {
    private val empty = ConversationAttachments()

    @Test fun concurrentFollowUpsDrainOnceInOneLeaseAndCannotOverfillQueue() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val continueFirst = CompletableDeferred<Unit>()
        val executed = ConcurrentLinkedQueue<String>()
        val acquisitions = AtomicInteger()
        val releases = AtomicInteger()
        val scheduler = MeetingTurnScheduler(owner, { _, _, _ -> acquisitions.incrementAndGet(); true },
            { releases.incrementAndGet() }, execute = { _, _, turn ->
                executed.add(turn.text)
                if (turn.text == "first") { entered.complete(Unit); continueFirst.await() }
                true
            }, retain = { _, _, _ -> error("completed turns must not be retained") })
        try {
            assertTrue(scheduler.start(RuntimeEnvironment.getApplication(), "room", "first", empty))
            withTimeout(3_000) { entered.await() }
            val accepted = (1..32).map { value -> async(Dispatchers.Default) {
                scheduler.enqueue("room", "follow-$value", empty)
            } }.awaitAll().count { it }
            assertEquals(8, accepted)
            assertEquals(8, scheduler.queuedCount("room"))
            assertFalse(scheduler.start(RuntimeEnvironment.getApplication(), "room", "overtake", empty))
            continueFirst.complete(Unit)
            withTimeout(3_000) { while (scheduler.isRunning("room")) delay(5) }
            assertEquals(9, executed.size)
            assertEquals(9, executed.toSet().size)
            assertEquals(1, acquisitions.get())
            assertEquals(1, releases.get())
        } finally { owner.cancel() }
    }

    @Test fun stopRetainsAcceptedInputsAndDoesNotLaunchNextTurn() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val retained = CompletableDeferred<List<QueuedMeetingTurn>>()
        val calls = AtomicInteger()
        val releases = AtomicInteger()
        val scheduler = MeetingTurnScheduler(owner, { _, _, _ -> true }, { releases.incrementAndGet() },
            execute = { _, _, _ -> calls.incrementAndGet(); entered.complete(Unit); awaitCancellation() },
            retain = { _, _, turns -> retained.complete(turns) })
        try {
            scheduler.start(RuntimeEnvironment.getApplication(), "room", "first", empty)
            withTimeout(3_000) { entered.await() }
            assertTrue(scheduler.enqueue("room", "second", empty))
            assertTrue(scheduler.stop("room"))
            assertFalse(scheduler.enqueue("room", "late", empty))
            assertEquals(listOf("first", "second"), withTimeout(3_000) { retained.await() }.map { it.text })
            withTimeout(3_000) { while (scheduler.isRunning("room")) delay(5) }
            assertEquals(1, calls.get())
            assertEquals(1, releases.get())
        } finally { owner.cancel() }
    }

    @Test fun stopDuringLeaseAcquisitionReleasesEvenWhenJobNeverStarted() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val retained = CompletableDeferred<List<QueuedMeetingTurn>>()
        val releases = AtomicInteger()
        val scheduler = MeetingTurnScheduler(owner, { _, _, stop -> stop(); true }, { releases.incrementAndGet() },
            execute = { _, _, _ -> error("must not execute") }, retain = { _, _, turns -> retained.complete(turns) })
        try {
            assertTrue(scheduler.start(RuntimeEnvironment.getApplication(), "room", "not started", empty))
            assertEquals("not started", withTimeout(3_000) { retained.await() }.single().text)
            withTimeout(3_000) { while (scheduler.isRunning("room")) delay(5) }
            assertEquals(1, releases.get())
        } finally { owner.cancel() }
    }

    @Test fun followUpIsAcceptedOnlyForAnActiveRoomAndIsBounded() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        assertFalse(MeetingRunCoordinator.enqueue(context, "missing", "ignored"))
        assertEquals(0, MeetingRunCoordinator.queuedCount("missing"))
    }

    @Test fun blankFollowUpAndAttachmentlessBlankStartAreRejected() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        assertFalse(MeetingRunCoordinator.start(context, "", ""))
        assertFalse(MeetingRunCoordinator.enqueue(context, "", ""))
    }
}
