package io.github.mangi.eta.agent.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceprintFeatureExtractorTest {
    @Test
    fun `short or silent recordings do not create an embedding`() {
        assertNull(VoiceprintFeatureExtractor.extract(pcm(samples = 1_000) { 0.0 }))
        assertNull(VoiceprintFeatureExtractor.extract(pcm(samples = 24_000) { 0.0 }))
    }

    @Test
    fun `embedding is deterministic and bounded`() {
        val input = pcm(samples = 24_000) { index ->
            .35 * sin(2 * PI * 180 * index / 16_000.0) +
                .18 * sin(2 * PI * 540 * index / 16_000.0)
        }
        val first = VoiceprintFeatureExtractor.extract(input)
        val second = VoiceprintFeatureExtractor.extract(input)
        assertEquals(VoiceprintFeatureExtractor.FEATURE_BYTES, first?.size)
        assertArrayEquals(first, second)
    }

    @Test
    fun `different spectral profiles are not identical`() {
        val low = pcm(samples = 24_000) { index -> .4 * sin(2 * PI * 150 * index / 16_000.0) }
        val high = pcm(samples = 24_000) { index ->
            .4 * sin(2 * PI * 150 * index / 16_000.0) +
                .2 * sin(2 * PI * 1_850 * index / 16_000.0)
        }
        assertNotEquals(
            VoiceprintFeatureExtractor.extract(low)?.toList(),
            VoiceprintFeatureExtractor.extract(high)?.toList(),
        )
    }

    @Test
    fun `normalized embedding tolerates recording gain changes`() {
        fun capture(gain: Double) = pcm(samples = 24_000) { index ->
            gain * (sin(2 * PI * 190 * index / 16_000.0) +
                .35 * sin(2 * PI * 760 * index / 16_000.0))
        }
        val original = requireNotNull(VoiceprintFeatureExtractor.extract(capture(.32)))
        val quieter = requireNotNull(VoiceprintFeatureExtractor.extract(capture(.16)))
        val dot = original.indices.sumOf { original[it].toInt() * quieter[it].toInt().toLong() }
        val left = sqrt(original.sumOf { it.toInt() * it.toInt().toDouble() })
        val right = sqrt(quieter.sumOf { it.toInt() * it.toInt().toDouble() })
        val similarity = dot / (left * right)
        org.junit.Assert.assertTrue("similarity=$similarity", similarity > .98)
    }

    private fun pcm(samples: Int, value: (Int) -> Double): ByteArray = ByteBuffer
        .allocate(samples * 2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            repeat(samples) { index ->
                putShort((value(index).coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort())
            }
        }
        .array()
}
