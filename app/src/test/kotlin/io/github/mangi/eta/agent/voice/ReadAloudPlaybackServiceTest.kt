package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import io.github.mangi.eta.R
import java.io.Closeable
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36, 37], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ReadAloudPlaybackServiceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller = Robolectric.buildService(TestService::class.java).create()
    private val service get() = controller.get()
    private val handles = mutableListOf<Closeable>()

    internal class TestService : ReadAloudPlaybackService() {
        var reject = false
        var promotions = 0
        override fun promotePlayback() {
            promotions++
            if (reject) throw SecurityException("test rejection")
            super.promotePlayback()
        }
    }

    private fun intent(): Intent = checkNotNull(shadowOf(context as Application).nextStartedService)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @After fun close() {
        scope.cancel()
        idle()
        handles.forEach(Closeable::close)
        controller.destroy()
    }

    @Test fun startWaitsForForegroundAcknowledgement() {
        val pending = scope.async { ReadAloudPlaybackService.start(context) {}.also(handles::add) }
        assertFalse(pending.isCompleted)
        service.onStartCommand(intent(), 0, 1)
        idle()
        assertTrue(pending.isCompleted)
        assertEquals(1, service.promotions)
        assertNotNull(shadowOf(service).lastForegroundNotification)
        runBlocking { pending.await() }
    }

    @Test fun synchronousLaunchRejectionReportsActionablePermissionError() {
        val rejected = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? = throw SecurityException("denied")
        }
        val pending = scope.async { ReadAloudPlaybackService.start(rejected) {} }
        val failure = assertThrows(SpeechFailure::class.java) { runBlocking { pending.await() } }
        assertEquals(SpeechErrorCode.PERMISSION, failure.code)
        assertEquals(context.getString(R.string.read_aloud_start_denied), failure.userMessage)
    }

    @Test fun asynchronousPromotionRejectionFailsAwaitingCaller() {
        service.reject = true
        val pending = scope.async { ReadAloudPlaybackService.start(context) {} }
        service.onStartCommand(intent(), 0, 1)
        idle()
        val failure = assertThrows(SpeechFailure::class.java) { runBlocking { pending.await() } }
        assertEquals(SpeechErrorCode.PERMISSION, failure.code)
    }

    @Test fun missingServiceCallbackTimesOutAndLateIntentStopsItself() {
        val pending = scope.async { ReadAloudPlaybackService.start(context) {} }
        val late = intent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        val failure = assertThrows(SpeechFailure::class.java) { runBlocking { pending.await() } }
        assertEquals(SpeechErrorCode.TIMEOUT, failure.code)
        service.onStartCommand(late, 0, 7)
        assertEquals(1, service.promotions)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun callerTimeoutRemainsCancellationAndReleasesPendingService() {
        val pending = scope.async {
            withTimeout(100) { ReadAloudPlaybackService.start(context) {} }
        }
        val late = intent()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertThrows(TimeoutCancellationException::class.java) { runBlocking { pending.await() } }
        service.onStartCommand(late, 0, 5)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun canceledStartupCannotAcknowledgeOrStopNewSession() {
        val first = scope.async { ReadAloudPlaybackService.start(context) {} }
        val late = intent()
        first.cancel()
        idle()
        val second = scope.async { ReadAloudPlaybackService.start(context) {}.also(handles::add) }
        val fresh = intent()
        service.onStartCommand(late, 0, 1)
        idle()
        assertFalse(second.isCompleted)
        assertFalse(shadowOf(service).isStoppedBySelf)
        service.onStartCommand(fresh, 0, 2)
        idle()
        assertTrue(second.isCompleted)
        runBlocking { second.await() }
    }

    @Test fun oldHandleAndOldStopNotificationDoNotStopReplacement() {
        var oldStopped = 0
        var newStopped = 0
        val first = scope.async { ReadAloudPlaybackService.start(context) { oldStopped++ }.also(handles::add) }
        service.onStartCommand(intent(), 0, 1)
        idle()
        val oldNotification = checkNotNull(shadowOf(service).lastForegroundNotification)
        val oldStop = shadowOf(oldNotification.actions.single().actionIntent).savedIntent
        val oldHandle = runBlocking { first.await() }
        val second = scope.async { ReadAloudPlaybackService.start(context) { newStopped++ }.also(handles::add) }
        service.onStartCommand(intent(), 0, 2)
        idle()
        oldHandle.close()
        service.onStartCommand(oldStop, 0, 3)
        assertEquals(1, oldStopped)
        assertEquals(0, newStopped)
        assertFalse(shadowOf(service).isStoppedBySelf)
        val newNotification = checkNotNull(shadowOf(service).lastForegroundNotification)
        service.onStartCommand(shadowOf(newNotification.actions.single().actionIntent).savedIntent, 0, 4)
        assertEquals(1, newStopped)
        runBlocking { second.await() }
    }

    @Test fun serviceDestructionInterruptsOnlyItsActivePlayback() {
        var stopped = 0
        val pending = scope.async { ReadAloudPlaybackService.start(context) { stopped++ }.also(handles::add) }
        service.onStartCommand(intent(), 0, 1)
        idle()
        runBlocking { pending.await() }
        service.onDestroy()
        assertEquals(1, stopped)
        handles.single().close()
        assertEquals(1, stopped)
    }
}
