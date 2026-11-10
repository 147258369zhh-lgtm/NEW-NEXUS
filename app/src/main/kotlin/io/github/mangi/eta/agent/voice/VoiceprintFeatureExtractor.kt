package io.github.mangi.eta.agent.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Produces a bounded speaker embedding from the recorded PCM. The vector combines cepstral
 * statistics with spectral shape cues, normalizes away recording gain, and persists no raw audio.
 * It is intentionally deterministic so enrollment and verification always use the same contract.
 */
internal object VoiceprintFeatureExtractor {
    const val FEATURE_BYTES = 48
    private const val FRAME_SAMPLES = 400
    private const val HOP_SAMPLES = 160
    private const val FFT_SIZE = 512
    private const val MEL_BANDS = 26
    private const val CEPSTERS = 13
    private const val MIN_AUDIO_MS = 900

    fun extract(pcm16: ByteArray, sampleRate: Int = SpeechRecorder.RATE): ByteArray? {
        if (sampleRate < 8_000 || pcm16.size < sampleRate * 2L * MIN_AUDIO_MS / 1_000) return null
        val input = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN)
        val samples = DoubleArray(pcm16.size / 2) { input.short / 32768.0 }
        if (samples.isEmpty()) return null

        val analyzed = mutableListOf<DoubleArray>()
        var globalEnergy = 0.0
        val frameEnergies = mutableListOf<Double>()
        var offset = 0
        while (offset + FRAME_SAMPLES <= samples.size) {
            val frame = DoubleArray(FRAME_SAMPLES) { index ->
                val position = offset + index
                val emphasized = samples[position] - if (position > 0) .97 * samples[position - 1] else 0.0
                emphasized * (.54 - .46 * cos(2.0 * PI * index / (FRAME_SAMPLES - 1)))
            }
            val energy = frame.sumOf { it * it } / frame.size
            globalEnergy += energy
            frameEnergies += energy
            offset += HOP_SAMPLES
        }
        if (frameEnergies.isEmpty()) return null
        val averageEnergy = globalEnergy / frameEnergies.size
        val threshold = max(averageEnergy * .20, 2.5e-6)

        offset = 0
        while (offset + FRAME_SAMPLES <= samples.size) {
            val frame = DoubleArray(FRAME_SAMPLES) { index ->
                val position = offset + index
                val emphasized = samples[position] - if (position > 0) .97 * samples[position - 1] else 0.0
                emphasized * (.54 - .46 * cos(2.0 * PI * index / (FRAME_SAMPLES - 1)))
            }
            val energy = frame.sumOf { it * it } / frame.size
            if (energy >= threshold) analyzed += frameFeatures(frame, sampleRate)
            offset += HOP_SAMPLES
        }
        if (analyzed.size < 8) return null

        val moments = DoubleArray(FEATURE_BYTES)
        val dimensions = analyzed.first().size
        val divisor = dimensions * 2
        analyzed.forEach { features ->
            features.forEachIndexed { index, value ->
                moments[index] += value / analyzed.size
                moments[dimensions + index] += value * value / analyzed.size
            }
        }
        val vector = DoubleArray(FEATURE_BYTES)
        for (index in 0 until dimensions) {
            val mean = moments[index]
            vector[index] = mean
            vector[dimensions + index] = sqrt(max(0.0, moments[dimensions + index] - mean * mean))
        }
        // Frame count is represented only as a bounded quality signal, never as speaker identity.
        vector[FEATURE_BYTES - 2] = ln(analyzed.size.toDouble() + 1.0)
        vector[FEATURE_BYTES - 1] = ln(analyzed.sumOf { frame -> frame[dimensions - 1] } / analyzed.size + 1.0)
        return normalizeAndQuantize(vector)
    }

    private fun frameFeatures(frame: DoubleArray, sampleRate: Int): DoubleArray {
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        frame.copyInto(real)
        fft(real, imaginary)
        val bins = FFT_SIZE / 2 + 1
        val magnitudes = DoubleArray(bins) { index ->
            val power = real[index] * real[index] + imaginary[index] * imaginary[index]
            sqrt(power)
        }
        val mel = melEnergies(magnitudes, sampleRate)
        val cepstrum = DoubleArray(CEPSTERS)
        for (coefficient in 0 until CEPSTERS) {
            var sum = 0.0
            mel.forEachIndexed { index, value ->
                sum += value * cos(PI * coefficient * (index + .5) / MEL_BANDS)
            }
            cepstrum[coefficient] = sum / MEL_BANDS
        }
        // C0 mostly tracks recording gain and microphone distance, not speaker identity.
        cepstrum[0] = 0.0
        val melFloor = mel.minOrNull() ?: 0.0
        val melWeights = mel.map { kotlin.math.exp(it - melFloor) }
        val melTotal = melWeights.sum().coerceAtLeast(1e-12)
        val spectralEntropy = melWeights.sumOf { value ->
            val probability = value / melTotal
            if (probability <= 0.0) 0.0 else -probability * ln(probability)
        }
        val total = magnitudes.sumOf { it * it }.coerceAtLeast(1e-12)
        val centroid = magnitudes.indices.sumOf { index -> index * magnitudes[index] * magnitudes[index] } / total
        val flatness = kotlin.math.exp(magnitudes.indices.sumOf { index ->
            ln(max(magnitudes[index], 1e-12)) / bins
        }) / max(magnitudes.average(), 1e-12)
        var crossing = 0
        for (index in 1 until frame.size) if ((frame[index] >= 0) != (frame[index - 1] >= 0)) crossing++
        val values = DoubleArray(FEATURE_BYTES / 2 - 2)
        cepstrum.copyInto(values)
        values[CEPSTERS] = spectralEntropy
        values[CEPSTERS + 1] = centroid / bins
        values[CEPSTERS + 2] = ln(flatness.coerceIn(1e-9, 1e9) + 1.0)
        values[CEPSTERS + 3] = crossing.toDouble() / frame.size
        values[CEPSTERS + 4] = dominantPitch(frame, sampleRate) / 400.0
        values[CEPSTERS + 5] = cepstrum.drop(1).sumOf(::abs)
        return values
    }

    private fun melEnergies(magnitudes: DoubleArray, sampleRate: Int): DoubleArray {
        fun hzToMel(value: Double) = 2595.0 * log10(1.0 + value / 700.0)
        fun melToHz(value: Double) = 700.0 * (10.0.pow(value / 2595.0) - 1.0)
        val maxHz = sampleRate / 2.0
        val left = hzToMel(80.0)
        val right = hzToMel(maxHz * .96)
        val points = DoubleArray(MEL_BANDS + 2) { melToHz(left + (right - left) * it / (MEL_BANDS + 1)) }
        return DoubleArray(MEL_BANDS) { band ->
            val low = points[band]
            val center = points[band + 1]
            val high = points[band + 2]
            var sum = 0.0
            magnitudes.forEachIndexed { bin, value ->
                val hz = bin * sampleRate.toDouble() / FFT_SIZE
                val weight = when {
                    hz in low..center -> (hz - low) / max(center - low, 1e-9)
                    hz in center..high -> (high - hz) / max(high - center, 1e-9)
                    else -> 0.0
                }
                sum += value * value * weight
            }
            ln(sum + 1e-12)
        }
    }

    private fun dominantPitch(frame: DoubleArray, sampleRate: Int): Double {
        val minimumLag = sampleRate / 350
        val maximumLag = sampleRate / 75
        var bestLag = minimumLag
        var best = -Double.MAX_VALUE
        for (lag in minimumLag..maximumLag) {
            var correlation = 0.0
            for (index in 0 until frame.size - lag) correlation += frame[index] * frame[index + lag]
            if (correlation > best) {
                best = correlation
                bestLag = lag
            }
        }
        return sampleRate.toDouble() / bestLag
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val size = real.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val realSwap = real[i]; real[i] = real[j]; real[j] = realSwap
                val imaginarySwap = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = imaginarySwap
            }
        }
        var length = 2
        while (length <= size) {
            val angle = -2.0 * PI / length
            for (start in 0 until size step length) {
                for (offset in 0 until length / 2) {
                    val cosine = cos(angle * offset)
                    val sine = sin(angle * offset)
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * cosine - imaginary[odd] * sine
                    val oddImaginary = real[odd] * sine + imaginary[odd] * cosine
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                }
            }
            length = length shl 1
        }
    }

    private fun normalizeAndQuantize(values: DoubleArray): ByteArray {
        val mean = values.average()
        val centered = DoubleArray(values.size) { values[it] - mean }
        val norm = sqrt(centered.sumOf { it * it }).coerceAtLeast(1e-12)
        return ByteArray(values.size) { index ->
            ((centered[index] / norm) * 12_000.0).toInt().coerceIn(-127, 127).toByte()
        }
    }
}
