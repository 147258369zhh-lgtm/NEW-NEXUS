package io.github.mangi.eta.agent.workflow

import org.junit.Assert.*
import org.junit.Test

class NotepadAttachmentHandoffTest {
    @Test fun disposalCleansOnlyCopiesNotYetDelivered() {
        val cleaned = mutableListOf<String>()
        val handoff = NotepadAttachmentHandoff { cleaned.addAll(it) }
        assertTrue(handoff.offer("delivered"))
        assertTrue(handoff.offer("pending"))
        val accepted = mutableListOf<String>()
        handoff.deliver("delivered") { accepted.add("delivered") }
        handoff.dispose()
        handoff.deliver("pending") { accepted.add("pending") }
        assertEquals(listOf("delivered"), accepted)
        assertEquals(listOf("pending"), cleaned)
        assertFalse(handoff.offer("late"))
    }
}
