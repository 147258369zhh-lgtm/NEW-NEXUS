package io.github.mangi.eta.ui.app

import android.os.Bundle
import io.github.mangi.eta.agent.accessibility.AccessibilityWaitPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.applyEvent
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.ui.model.*
import org.junit.Assert.*
import android.app.Application
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [33, 36, 37], application = Application::class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
class AccessibilityWaitProjectionTest {
    @Test fun liveReconnectRetainsWaitingButDiskRecoveryDoesNotRecreateALiveWait() {
        val event = AgentEvent.AccessibilityWaiting(2, AccessibilityWaitPhase.WAITING)
        assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
        assertEquals(event, event.replayProjection())
        val restored = AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(event)) as AgentEvent.AccessibilityWaiting
        assertEquals(AccessibilityWaitPhase.CANCELLED, restored.phase)
        val projector = AgentRunMessageProjector()
        assertFalse((projector.applyEvent("r", restored, emptyList()).single() as SystemNoticeMessageUi).running)
        AccessibilityWaitPhase.entries.filter { it != AccessibilityWaitPhase.WAITING }.forEach { phase ->
            val terminal = event.copy(phase = phase)
            assertEquals(terminal, AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(terminal)))
        }
    }

    @Test fun unknownFuturePhaseIsIgnoredWithoutInventingARunningState() {
        val bundle = Bundle().apply { putString("type", "accessibility_wait"); putString("phase", "future") }
        assertNull(AgentRuntimeWire.eventFromBundle(bundle))
    }

    @Test fun phaseUpdatesKeepTheOriginalTimelinePositionAndOutageIdentity() {
        val projector = AgentRunMessageProjector()
        val waiting = AgentEvent.AccessibilityWaiting(1, AccessibilityWaitPhase.WAITING)
        val original = projector.applyEvent("r", waiting, listOf(UserMessageUi("u", "request")))
        val appended = original + AgentMessageUi("assistant-r-1-text-0", "later")
        val restored = projector.applyEvent("r", waiting.copy(phase = AccessibilityWaitPhase.RESTORED), appended)
        assertEquals(appended.map { it.id }, restored.map { it.id })
        val notice = restored[1] as SystemNoticeMessageUi
        assertEquals("restored", notice.detail)
        assertFalse(notice.running)
        val second = projector.applyEvent("r", waiting.copy(outage = 2), restored)
        assertEquals(4, second.size)
        assertEquals(2, second.filterIsInstance<SystemNoticeMessageUi>().size)
    }

    @Test fun terminalResultClearsWaitingOnlyForItsOwnRunEvenWithoutFinalEvent() {
        val projector = AgentRunMessageProjector()
        val event = AgentEvent.AccessibilityWaiting(1, AccessibilityWaitPhase.WAITING)
        val messages = projector.applyEvent("b", event, projector.applyEvent("a", event, emptyList()))
        val ended = AgentRunMessageProjector.applyResult("a", messages, "done", null)
        val notices = ended.filterIsInstance<SystemNoticeMessageUi>()
        assertFalse(notices.first { it.id.contains("assistant-a-") }.running)
        assertTrue(notices.first { it.id.contains("assistant-b-") }.running)
        val failed = projector.applyEvent("b", AgentEvent.RunFailed("cancelled"), ended)
        assertFalse(failed.filterIsInstance<SystemNoticeMessageUi>().any { it.running })
    }

    @Test fun overlayAndNotificationHaveAnExplicitWaitingStatusAndRespectManualPause() {
        val event = AgentEvent.AccessibilityWaiting(1, AccessibilityWaitPhase.WAITING)
        val waiting = AgentOverlayState.Initial.applyEvent(event)
        assertEquals(AgentOverlayStatus.WaitingAccessibility, waiting.status)
        assertEquals(AgentOverlayStatus.Continuing, waiting.applyEvent(event.copy(phase = AccessibilityWaitPhase.RESTORED)).status)
        val paused = waiting.copy(phase = AgentOverlayPhase.PAUSED, status = AgentOverlayStatus.Paused)
        assertEquals(paused, paused.applyEvent(event.copy(phase = AccessibilityWaitPhase.RESTORED)))
    }
}
