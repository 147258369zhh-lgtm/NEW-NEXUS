package io.github.mangi.eta.agent.voice

import android.os.SystemClock
import org.json.JSONObject

internal data class LocalSpeechMetrics(val modelId: String, val kind: LocalSpeechKind, val backend: String,
    val engine: String, val elapsedMs: Long, val firstResultMs: Long?, val inputSamples: Long,
    val outputSamples: Long, val inputSampleRate: Int, val outputSampleRate: Int, val cachedModel: Boolean,
    val successful: Boolean, val failureCode: String?) {
    val inputAudioMs get() = inputSamples * 1_000 / inputSampleRate
    val outputAudioMs get() = outputSamples * 1_000 / outputSampleRate
    val realTimeFactor: Double? get() = (if (kind == LocalSpeechKind.ASR) inputAudioMs else outputAudioMs)
        .takeIf { it > 0 }?.let { elapsedMs.toDouble() / it }
    fun json() = JSONObject().put("modelId", modelId).put("kind", kind.name).put("backend", backend).put("engine", engine)
        .put("elapsedMs", elapsedMs).put("firstResultMs", firstResultMs ?: JSONObject.NULL)
        .put("inputAudioMs", inputAudioMs).put("outputAudioMs", outputAudioMs)
        .put("realTimeFactor", realTimeFactor ?: JSONObject.NULL).put("cachedModel", cachedModel)
        .put("successful", successful).put("failureCode", failureCode ?: JSONObject.NULL)
        .put("tokenCacheHitRate", JSONObject.NULL)
}

/** Audio metrics stay in audio units; unavailable token/cache counters are never invented. */
internal class SpeechMetricsMeasurement(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val started = clock()
    private var first: Long? = null
    private var input = 0L
    private var output = 0L
    fun input(pcm16: ByteArray) { input += pcm16.size / 2 }
    fun output(pcm16: ByteArray) { output += pcm16.size / 2; if (pcm16.isNotEmpty()) result() }
    fun result() { if (first == null) first = (clock() - started).coerceAtLeast(0) }
    fun finish(modelId: String, kind: LocalSpeechKind, backend: String, engine: String, cached: Boolean,
        successful: Boolean, failureCode: String?) = LocalSpeechMetrics(modelId, kind, backend, engine,
        (clock() - started).coerceAtLeast(0), first, input, output, 16_000, 24_000, cached, successful, failureCode)
}
