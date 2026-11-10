package io.github.mangi.eta.fork.meeting

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentEvent.AssistantBlockKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MeetingStreamReducerTest {
    private fun delta(text: String, index: Int = 0, round: Int = 1, kind: AssistantBlockKind = AssistantBlockKind.TEXT) =
        AgentEvent.AssistantBlockDelta(round, kind, index, text.length, text)
    private fun end(text: String?, index: Int = 0, round: Int = 1, kind: AssistantBlockKind = AssistantBlockKind.TEXT) =
        AgentEvent.AssistantBlockEnd(round, kind, index, contentChars = text?.length ?: 0, replacementContent = text)

    @Test fun deltasAreCoalescedByRoundAndKindWithAuthoritativeBlockReplacement() {
        val reducer = MeetingStreamReducer("run")
        reducer.accept(delta("preliminary")); reducer.accept(delta(" body"))
        val first = reducer.drain().single()
        assertEquals("preliminary body", first.content)
        assertEquals(MeetingStreamPhase.STREAMING, first.phase)
        assertTrue(reducer.drain().isEmpty())
        reducer.accept(end("corrected first"))
        reducer.accept(delta("second", index = 2))
        reducer.accept(end(null, index = 2))
        val result = reducer.drain().single()
        assertEquals(first.messageId, result.messageId)
        assertEquals("corrected first\n\nsecond", result.content)
        assertEquals(MeetingStreamPhase.COMPLETED, result.phase)
        reducer.accept(delta("late replay"))
        assertTrue(reducer.drain().isEmpty())
    }

    @Test fun toolAndRoundBoundariesCompletePriorOutputAndKeepThinkingSeparate() {
        val reducer = MeetingStreamReducer("run")
        reducer.accept(delta("reason", kind = AssistantBlockKind.THINKING))
        reducer.accept(delta("checking"))
        reducer.accept(AgentEvent.ToolStarted(1, "call", "read_file", "read"))
        val first = reducer.drain()
        assertEquals(2, first.size)
        assertTrue(first.all { it.phase == MeetingStreamPhase.COMPLETED })
        assertNotEquals(first[0].messageId, first[1].messageId)
        reducer.accept(delta("final", round = 2))
        reducer.accept(AgentEvent.RunFinished(2, 5))
        val last = reducer.drain().single()
        assertEquals(2, last.round)
        assertEquals(MeetingStreamPhase.COMPLETED, last.phase)
        reducer.accept(delta("late", index = 4, round = 3))
        assertTrue(reducer.drain().isEmpty())
    }

    @Test fun retryPreservesPartialOutputAndUsesANewAttemptRatherThanAppendingToIt() {
        val reducer = MeetingStreamReducer("run")
        reducer.accept(delta("partial"))
        reducer.accept(AgentEvent.ModelRetryScheduled(1, 1, 2, 100, "network"))
        val partial = reducer.drain().single()
        assertEquals(MeetingStreamPhase.INTERRUPTED, partial.phase)
        reducer.accept(delta("replacement"))
        reducer.accept(end("replacement complete"))
        val next = reducer.drain().single()
        assertEquals(1, next.attempt)
        assertEquals("replacement complete", next.content)
        assertNotEquals(partial.messageId, next.messageId)
    }

    @Test fun cancellationPreservesCompletedTextAndMarksOnlyUnfinishedStreamsInterrupted() {
        val reducer = MeetingStreamReducer("run")
        reducer.accept(delta("completed")); reducer.accept(end(null))
        reducer.accept(delta("partial reasoning", kind = AssistantBlockKind.THINKING))
        reducer.finish()
        val result = reducer.drain()
        assertEquals(MeetingStreamPhase.COMPLETED, result.first().phase)
        assertEquals(MeetingStreamPhase.INTERRUPTED, result.last().phase)
        assertEquals("partial reasoning", result.last().content)
    }

    @Test fun previewIsExplicitlyBoundedWhileMetadataDoesNotDuplicateItsBody() {
        val reducer = MeetingStreamReducer("run")
        reducer.accept(delta("private-body-" + "x".repeat(MeetingStreamActivity.MAX_PREVIEW_CHARS)))
        val result = reducer.drain().single()
        assertEquals(MeetingStreamActivity.MAX_PREVIEW_CHARS, result.content.length)
        assertTrue(result.truncated)
        assertFalse(result.encode(false).contains("private-body-"))
        assertEquals(result, MeetingStreamActivity.decode(result.encode(false), result.content))
    }

    @Test fun reasoningFallbackIsShownOnceAndToolArgumentsNeverEnterVisibleStreams() {
        val reducer = MeetingStreamReducer("run")
        reducer.accept(AgentEvent.AssistantReceived(1, 0, "reasoning fallback", emptyList()))
        reducer.accept(delta("secret", kind = AssistantBlockKind.TOOL_CALL))
        reducer.accept(AgentEvent.AssistantReceived(1, 0, "replayed reasoning", emptyList()))
        val result = reducer.drain().single()
        assertEquals(AssistantBlockKind.THINKING, result.kind)
        assertEquals("reasoning fallback", result.content)
        assertEquals(MeetingStreamPhase.COMPLETED, result.phase)
    }

    @Test fun mailboxCombinesAdjacentDeltasButPreservesToolAndKindBoundaries() = runBlocking {
        val mailbox = MeetingRunEventMailbox()
        repeat(500) { mailbox.trySend(delta("x")) }
        val tool = AgentEvent.ToolStarted(1, "call", "read_file", "read")
        mailbox.trySend(tool)
        mailbox.trySend(delta("reason", kind = AssistantBlockKind.THINKING))
        mailbox.trySend(delta("answer"))
        mailbox.close()
        assertEquals("x".repeat(500), (mailbox.receiveCatching().getOrNull() as AgentEvent.AssistantBlockDelta).delta)
        assertEquals(tool, mailbox.receiveCatching().getOrNull())
        assertEquals(AssistantBlockKind.THINKING, (mailbox.receiveCatching().getOrNull() as AgentEvent.AssistantBlockDelta).kind)
        assertEquals("answer", (mailbox.receiveCatching().getOrNull() as AgentEvent.AssistantBlockDelta).delta)
        assertNull(mailbox.receiveCatching().getOrNull())
        mailbox.trySend(delta("late"))
        assertNull(mailbox.receiveCatching().getOrNull())
    }

    @Test fun aSlowWriterMailboxBoundsOneContinuousPreviewAndSignalsTruncation() = runBlocking {
        val mailbox = MeetingRunEventMailbox()
        repeat(1_000) { mailbox.trySend(delta("x".repeat(1_000))) }
        mailbox.close()
        val merged = mailbox.receiveCatching().getOrNull() as AgentEvent.AssistantBlockDelta
        assertEquals(MeetingStreamActivity.MAX_PREVIEW_CHARS + 1, merged.delta.length)
        val reducer = MeetingStreamReducer("run"); reducer.accept(merged)
        assertTrue(reducer.drain().single().truncated)
    }
}
