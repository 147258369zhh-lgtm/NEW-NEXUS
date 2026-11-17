package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import android.app.Application
import com.google.ai.edge.litertlm.Backend
import java.io.File
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.repository.LocalModelFormat
import io.github.mangi.eta.data.repository.LocalModelRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LocalEmbeddingRuntimeTest {
    @Test fun fakeEngineComputesVectorAndStopsWithMetrics() = runBlocking {
        val file = File.createTempFile("embedding-fixture", ".litertlm")
        var closed = false
        val fake = object : LocalEmbeddingRuntime.Engine {
            override fun initialize() = Unit
            override fun compute(text: String) = floatArrayOf(.1f, .2f)
            override fun close() { closed = true }
        }
        val context = Application()
        val record = testRecord(file)
        LocalEmbeddingRuntime.withTestEngine({ _, _, _: Backend -> fake }, { 
            LocalEmbeddingRuntime.warmUp(context, record)
            val result = LocalEmbeddingRuntime.embed(context, record, "hello")
            assertArrayEquals(floatArrayOf(.1f, .2f), result.vector, 0f)
            assertEquals(2, result.dimension)
            assertEquals(2, LocalEmbeddingRuntime.state.value.metrics?.vectorDimension)
            LocalEmbeddingRuntime.stop(record.id)
        }, inspect = { 2 }, resolve = { _, _ -> file })
        assertTrue(closed)
        file.delete()
    }

    @Test fun stopCancelsInFlightEmbeddingAndClosesOwner() = runBlocking {
        val file = File.createTempFile("embedding-fixture", ".litertlm")
        val entered = CompletableDeferred<Unit>()
        var closed = false
        val fake = object : LocalEmbeddingRuntime.Engine {
            override fun initialize() = Unit
            override fun compute(text: String): FloatArray { entered.complete(Unit); Thread.sleep(250); return floatArrayOf(1f) }
            override fun close() { closed = true }
        }
        val context = Application()
        val record = testRecord(file)
        LocalEmbeddingRuntime.withTestEngine({ _, _, _: Backend -> fake }, {
            val work = async { LocalEmbeddingRuntime.embed(context, record, "cancel") }
            entered.await()
            withTimeout(2_000) { LocalEmbeddingRuntime.stop(record.id) }
            assertTrue(work.isCancelled)
            assertTrue(closed)
        }, inspect = { 1 }, resolve = { _, _ -> file })
        file.delete()
    }

    @Test fun statusJsonExcludesVectorData() {
        val json = LocalEmbeddingRuntimeSnapshot(recordId = "embedding", phase = LocalEmbeddingRuntimePhase.READY, activeBackend = "CPU", dimension = 384).json()
        assertEquals("embedding", json.getString("recordId"))
        assertEquals(384, json.getInt("dimension"))
        assertFalse(json.has("vector"))
        assertFalse(json.has("input"))
    }

    private fun testRecord(file: File) = LocalModelRecord(
        id = "embedding-test", displayName = "Embedding test", fileName = file.absolutePath,
        format = LocalModelFormat.LITERT_LM, sizeBytes = 1, sha256 = "a".repeat(64),
        inputModalities = listOf(Model.TEXT_MODALITY), outputModalities = listOf("embedding"),
    )
}
