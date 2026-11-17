package io.github.mangi.eta.agent.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import io.github.mangi.eta.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

internal data class SpeakerFeatures(val bytes: ByteArray, val modelIdentity: String)

/** One shared speaker extractor; its identity includes the model content hash and vector schema. */
internal object LocalSpeakerModel {
    private val mutex = Mutex()
    private data class Cached(val identity: String, val threads: Int, val engine: SpeakerEmbeddingExtractor)
    private var cached: Cached? = null
    suspend fun extract(context: Context, pcm: ByteArray, manualRetry: Boolean = false): SpeakerFeatures? = withContext(Dispatchers.Default) {
        val id = WakeWordStore(context).read().speakerModelId
        val record = LocalModelRepository.record(id) ?: throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请先选择声纹模型")
        require(record.format == LocalModelFormat.ONNX && "speaker_embedding" in record.outputModalities) { "所选文件不是声纹模型" }
        if (pcm.size < SpeechRecorder.RATE * 2) return@withContext null
        var voicedBytes = 0
        for (start in pcm.indices step SpeechRecorder.FRAME_BYTES) {
            val frame = pcm.copyOfRange(start, minOf(start + SpeechRecorder.FRAME_BYTES, pcm.size))
            if (pcm16Rms(frame) >= SPEECH_VOICE_ACTIVITY_RMS) voicedBytes += frame.size
        }
        if (voicedBytes < SpeechRecorder.RATE) return@withContext null
        mutex.withLock {
            check(LocalModelRepository.record(id)?.sha256 == record.sha256 && WakeWordStore(context).read().speakerModelId == id) { "声纹模型已切换或移除" }
            val engineId = "sherpa-speaker@2.0.1"
            if (manualRetry) LocalSpeechNativeCrashGuard.confirmRetry(record, LocalSpeechKind.ASR, engineId)
            check(!LocalSpeechNativeCrashGuard.hasNativeFailure(record, LocalSpeechKind.ASR, engineId)) { "声纹模型上次发生原生崩溃，请重新录入重试" }
            val attempt = LocalSpeechNativeCrashGuard.beginAttempt(record, LocalSpeechKind.ASR, LocalRuntimeBackendPolicy.CPU,
                "speaker-embedding", engineId)
            try {
                val identity = "sherpa-speaker-v1:${record.sha256}"
                val threads = record.runtimeSettings.threads.coerceIn(1, 4)
                val existing = cached
                if (existing?.identity != identity || existing.threads != threads) {
                    existing?.engine?.release(); cached = null
                    val artifact = LocalModelRepository.artifactFile(context, record)
                    val file = if (artifact.isDirectory) requireNotNull(SpeechModelPackage.inspect(artifact)?.path("model")?.takeIf(String::isNotBlank)) else artifact.absolutePath
                    val engine = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = file, numThreads = threads, provider = "cpu"))
                    cached = Cached(identity, threads, engine)
                }
                val engine = checkNotNull(cached).engine
                val stream = engine.createStream()
                try {
                    currentCoroutineContext().ensureActive()
                    stream.acceptWaveform(pcm16ToFloat(pcm), SpeechRecorder.RATE); stream.inputFinished()
                    if (!engine.isReady(stream)) return@withLock null
                    val vector = engine.compute(stream)
                    currentCoroutineContext().ensureActive()
                    quantize(vector)?.let { SpeakerFeatures(it, identity) }
                } finally { stream.release() }
            } finally { attempt.completed() }
        }
    }

    suspend fun release(recordId: String? = null) = mutex.withLock {
        releaseCached(recordId)
    }
    suspend fun <T> whileStopped(recordId: String, action: suspend () -> T): T = mutex.withLock {
        releaseCached(recordId); action()
    }
    private fun releaseCached(recordId: String?) {
        val value = cached ?: return
        if (recordId != null) {
            val record = LocalModelRepository.record(recordId) ?: return
            if (!value.identity.endsWith(record.sha256)) return
        }
        cached = null; value.engine.release()
    }

    internal fun quantize(vector: FloatArray): ByteArray? {
        if (vector.size !in 16..4096 || vector.any { !it.isFinite() }) return null
        val norm = sqrt(vector.sumOf { it.toDouble() * it }).takeIf { it > 1e-9 } ?: return null
        val normalized = vector.map { it / norm }
        val scale = normalized.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: return null
        return ByteArray(vector.size) { (normalized[it] / scale * 127).toInt().coerceIn(-127, 127).toByte() }
    }
}
