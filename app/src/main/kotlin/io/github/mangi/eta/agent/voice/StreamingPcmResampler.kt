package io.github.mangi.eta.agent.voice

/** Stateful linear resampler that preserves phase across streamed audio chunks. */
internal class StreamingPcmResampler(
    private val sourceRate: Int,
    private val targetRate: Int = 24_000,
) {
    init {
        require(sourceRate > 0 && targetRate > 0) { "Audio sample rates must be positive" }
    }

    private val ratio = sourceRate.toDouble() / targetRate
    private var consumedSamples = 0L
    private var nextOutputPosition = 0.0
    private var previous: Float? = null

    fun process(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        if (sourceRate == targetRate) {
            consumedSamples += samples.size
            previous = samples.last()
            return samples.copyOf()
        }
        val start = consumedSamples
        val end = start + samples.size
        val output = ArrayList<Float>((samples.size / ratio).toInt() + 1)
        while (nextOutputPosition < end) {
            val local = nextOutputPosition - start
            val leftIndex = kotlin.math.floor(local).toInt()
            val fraction = (local - leftIndex).toFloat()
            val left = when {
                leftIndex < 0 -> previous ?: samples.first()
                leftIndex < samples.size -> samples[leftIndex]
                else -> samples.last()
            }
            val right = if (leftIndex + 1 in samples.indices) samples[leftIndex + 1] else samples.last()
            output += left + (right - left) * fraction
            nextOutputPosition += ratio
        }
        consumedSamples = end
        previous = samples.last()
        return output.toFloatArray()
    }
}
