package io.github.mangi.eta.ui.components

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.IntSize
import io.github.mangi.eta.data.model.ConversationSubject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ConversationPaneSourceTapTest {
    @Test
    fun titleTapsOpenBothChatAndMeetingRowsEvenWhenNativeDragConsumedDown() = runBlocking {
        for (subject in listOf(ConversationSubject.chat("chat-b"), ConversationSubject.meeting("room-b"))) {
            val down = change(0, pressed = true, previouslyPressed = false).also { it.consume() }
            val up = change(80, pressed = false, previouslyPressed = true)
            val input = Input(listOf(PointerEvent(listOf(down)), PointerEvent(listOf(up))))
            val opened = mutableListOf<ConversationSubject>()

            runToEnd(input) { opened += subject }

            assertEquals(listOf(subject), opened)
            assertTrue("The release must not invoke the parent row a second time", up.isConsumed)
        }
    }

    @Test
    fun sourceDoesNotConsumeDownNeededForNativeLongPressAndMouseDrag() = runBlocking {
        for (type in listOf(PointerType.Touch, PointerType.Mouse)) {
            val down = change(0, pressed = true, previouslyPressed = false, type = type)
            val up = change(80, pressed = false, previouslyPressed = true, type = type)
            val input = Input(listOf(PointerEvent(listOf(down)), PointerEvent(listOf(up))))
            var clicks = 0

            runToEnd(input) { clicks++ }

            // An unchanged parent row may also have seen down; the claimed release prevents
            // its existing click detector from dispatching the selection a second time.
            if (!up.isConsumed) clicks++

            assertFalse(down.isConsumed)
            assertEquals(1, clicks)
        }
    }

    @Test
    fun scrollingOrNativeDraggingCancelsTheTapInsteadOfOpeningTheRow() = runBlocking {
        val down = change(0, pressed = true, previouslyPressed = false)
        val movement = change(40, pressed = true, previouslyPressed = true).also { it.consume() }
        val up = change(80, pressed = false, previouslyPressed = true)
        val input = Input(listOf(PointerEvent(listOf(down)), PointerEvent(listOf(movement)), PointerEvent(listOf(up))))
        var clicks = 0

        runToEnd(input) { clicks++ }

        assertEquals(0, clicks)
        assertFalse(up.isConsumed)
    }

    @Test
    fun longPressRemainsOwnedByNativeDragAndDoesNotSelectTheRowOnRelease() = runBlocking {
        val down = change(0, pressed = true, previouslyPressed = false)
        val up = change(700, pressed = false, previouslyPressed = true)
        val input = Input(listOf(PointerEvent(listOf(down)), PointerEvent(listOf(up))), longPress = true)
        var clicks = 0

        runToEnd(input) { clicks++ }

        assertEquals(0, clicks)
        assertFalse(down.isConsumed)
        assertFalse(up.isConsumed)
    }

    @Test
    fun cancelledMultiPointerAndOverSlopGesturesNeverSelectARow() = runBlocking {
        val cases = listOf(
            listOf(PointerEvent(emptyList())),
            listOf(
                PointerEvent(listOf(change(40, pressed = true, previouslyPressed = true, x = 70f))),
                PointerEvent(listOf(change(80, pressed = false, previouslyPressed = true, x = 70f))),
            ),
            listOf(
                PointerEvent(listOf(
                    change(40, pressed = true, previouslyPressed = true),
                    change(40, pressed = true, previouslyPressed = false, id = 2),
                )),
                PointerEvent(listOf(
                    change(80, pressed = false, previouslyPressed = true),
                    change(80, pressed = false, previouslyPressed = true, id = 2),
                )),
            ),
        )
        for (events in cases) {
            val down = PointerEvent(listOf(change(0, pressed = true, previouslyPressed = false)))
            val input = Input(listOf(down) + events)
            var clicks = 0

            runToEnd(input) { clicks++ }

            assertEquals(0, clicks)
            assertTrue(events.flatMap { it.changes }.none { it.isConsumed })
        }
    }

    private fun change(time: Long, pressed: Boolean, previouslyPressed: Boolean, type: PointerType = PointerType.Touch,
        id: Long = 1, x: Float = 40f) =
        PointerInputChange(
            id = PointerId(id), uptimeMillis = time, position = Offset(x, 30f), pressed = pressed,
            previousUptimeMillis = if (time == 0L) 0L else time - 40,
            previousPosition = Offset(40f, 30f), previousPressed = previouslyPressed,
            isInitiallyConsumed = false, type = type,
        )

    /** Feeds the same pointer changes through Initial and Final, as Compose's input passes do. */
    private class Input(events: List<PointerEvent>, private val longPress: Boolean = false) :
        PointerInputScope, AwaitPointerEventScope {
        private val pending = ArrayDeque(events)
        override val size = IntSize(300, 60)
        override val extendedTouchPadding = Size.Zero
        override val density = 1f
        override val fontScale = 1f
        override var currentEvent = PointerEvent(emptyList())
            private set
        private var lastPass: PointerEventPass? = null
        override val viewConfiguration = object : ViewConfiguration {
            override val longPressTimeoutMillis = 500L
            override val doubleTapTimeoutMillis = 300L
            override val doubleTapMinTimeMillis = 40L
            override val touchSlop = 18f
        }

        override suspend fun <R> awaitPointerEventScope(block: suspend AwaitPointerEventScope.() -> R): R =
            suspendCoroutine { caller ->
                // Compose's restricted pointer scope runs without the caller's dispatcher/Job.
                block.startCoroutine(this, object : Continuation<R> {
                    override val context = EmptyCoroutineContext
                    override fun resumeWith(result: Result<R>) = caller.resumeWith(result)
                })
            }

        override suspend fun awaitPointerEvent(pass: PointerEventPass): PointerEvent {
            if (pass == PointerEventPass.Final && lastPass != PointerEventPass.Final) {
                lastPass = pass
                return currentEvent
            }
            lastPass = pass
            currentEvent = pending.removeFirstOrNull() ?: throw Finished()
            return currentEvent
        }

        override suspend fun <T> withTimeoutOrNull(timeMillis: Long, block: suspend AwaitPointerEventScope.() -> T): T? =
            if (longPress) null else block(this)

    }

    private suspend fun runToEnd(input: PointerInputScope, onClick: () -> Unit) {
        try { input.detectPaneSourceTaps(onClick) } catch (_: Finished) { /* Detector is intentionally a gesture loop. */ }
    }

    private class Finished : RuntimeException()
}
