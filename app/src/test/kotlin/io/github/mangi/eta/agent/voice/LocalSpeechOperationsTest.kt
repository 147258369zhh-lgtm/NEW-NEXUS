package io.github.mangi.eta.agent.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class LocalSpeechOperationsTest {
    @Test fun stoppingAsrCancelsItsStreamAndQueuedRequestsWithoutInterruptingTts() = runBlocking {
        val operations = LocalSpeechOperations()
        val asrEntered = CompletableDeferred<Unit>(); val ttsEntered = CompletableDeferred<Unit>(); val queueEntered = CompletableDeferred<Unit>()
        val asrMutex = Mutex(); val frames = Channel<ByteArray>(); val ttsRelease = CompletableDeferred<Unit>()
        val asr = async { runCatching { operations.run("asr", LocalSpeechKind.ASR) { asrMutex.withLock {
            asrEntered.complete(Unit); for (frame in frames) Unit
        } } } }
        val tts = async { operations.run("tts", LocalSpeechKind.TTS) { ttsEntered.complete(Unit); ttsRelease.await() } }
        asrEntered.await(); ttsEntered.await()
        val queued = async { runCatching { operations.run("asr", LocalSpeechKind.ASR) {
            queueEntered.complete(Unit); asrMutex.withLock { fail("Stopped queue must not enter native code") }
        } } }
        queueEntered.await()
        try {
            assertEquals(setOf("asr"), operations.records(LocalSpeechKind.ASR))
            assertEquals(setOf("tts"), operations.records(LocalSpeechKind.TTS))
            operations.stop("asr").use { stop ->
                assertEquals(setOf(LocalSpeechKind.ASR), stop.kinds)
                withTimeout(1_000) { asr.join(); queued.join() }
                assertTrue(asr.await().exceptionOrNull() is CancellationException)
                assertTrue(queued.await().exceptionOrNull() is CancellationException)
                assertFalse(tts.isCompleted)
                assertFalse(operations.isInUse(LocalSpeechKind.ASR)); assertTrue(operations.isInUse(LocalSpeechKind.TTS))
                assertTrue(operations.records(LocalSpeechKind.ASR).isEmpty())
                assertThrows(IllegalStateException::class.java) { runBlocking { operations.run("asr", LocalSpeechKind.ASR) {} } }
            }
            assertEquals("restarted", operations.run("asr", LocalSpeechKind.ASR) { "restarted" })
        } finally { frames.close(); asr.cancelAndJoin(); queued.cancelAndJoin(); ttsRelease.complete(Unit); tts.cancelAndJoin() }
    }

    @Test fun overlappingStopsKeepTheModelBlockedUntilBothOwnersClose() = runBlocking {
        val operations = LocalSpeechOperations()
        val first = operations.stop("model"); val second = operations.stop("model")
        first.close(); first.close()
        assertTrue(operations.isStopping("model"))
        second.close()
        assertFalse(operations.isStopping("model"))
        assertEquals(42, operations.run("model", LocalSpeechKind.TTS) { 42 })
    }

    @Test fun failedOperationsDoNotLeaveAnOwnerOrPreventTheNextRequest() = runBlocking {
        val operations = LocalSpeechOperations()
        assertTrue(runCatching { operations.run("model", LocalSpeechKind.TTS) { error("fixture") } }.isFailure)
        assertFalse(operations.isInUse(LocalSpeechKind.TTS))
        operations.run("model", LocalSpeechKind.TTS) { assertTrue(operations.isInUse(LocalSpeechKind.TTS)) }
        assertFalse(operations.isInUse(LocalSpeechKind.TTS))
    }
}
