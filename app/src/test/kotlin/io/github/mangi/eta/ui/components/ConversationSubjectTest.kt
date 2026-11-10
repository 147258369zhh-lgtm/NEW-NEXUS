package io.github.mangi.eta.ui.components

import io.github.mangi.eta.data.model.ConversationSubject
import org.junit.Assert.*
import org.junit.Test

class ConversationSubjectTest {
    @Test fun kindAndLengthPreventCollisionsWithUserControlledIds() {
        val values = listOf(ConversationSubject.chat(null), ConversationSubject.chat("draft"),
            ConversationSubject.chat("same"), ConversationSubject.meeting("same"),
            ConversationSubject.chat("meeting:4:same"), ConversationSubject.meeting("chat:4:same"))
        assertEquals(values.size, values.map { it.key }.toSet().size)
        assertEquals(ConversationSubject.chat("same").key, chatConversationCompositionKey("same"))
    }
    @Test fun malformedEmptyIdsCannotEnterTheStore() {
        assertThrows(IllegalArgumentException::class.java) { ConversationSubject.chat("") }
        assertThrows(IllegalArgumentException::class.java) { ConversationSubject.meeting(" ") }
        assertThrows(IllegalArgumentException::class.java) { ConversationSubject(ConversationSubject.Kind.NEW_CHAT, "real-id") }
    }
}
