package io.github.mangi.eta.agent.model

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import io.github.mangi.eta.agent.voice.LocalSpeechRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Release low-priority speech first and measure again before evicting the text model. */
internal class LocalModelMemoryRelief(
    private val releaseSpeech: suspend (Int) -> Unit,
    private val stillLowMemory: () -> Boolean,
    private val releaseText: (Boolean) -> Unit,
) {
    private val mutex = Mutex()
    suspend fun relieve(level: Int) = mutex.withLock {
        releaseSpeech(level)
        if (stillLowMemory()) releaseText(level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }
}

internal object LocalModelResourceOwner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var context: Context? = null
    private var relief: LocalModelMemoryRelief? = null
    @Synchronized private fun forContext(value: Context): LocalModelMemoryRelief {
        val app = value.applicationContext
        if (context !== app) {
            context = app
            fun lowMemory(): Boolean {
                val memory = ActivityManager.MemoryInfo()
                app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
                return memory.lowMemory
            }
            relief = LocalModelMemoryRelief({ level ->
                LocalEmbeddingRuntime.releaseForMemory(level)
                if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                    io.github.mangi.eta.agent.voice.BackgroundWakeWordRuntime.memoryPressure()
                    io.github.mangi.eta.agent.voice.LocalSpeakerModel.release()
                }
                val selected = io.github.mangi.eta.data.datastore.SettingsDataStore.settings().selectedProviderId
                val primaryLocal = selected in setOf(io.github.mangi.eta.data.repository.LocalModelRepository.BUILTIN_PROVIDER_ID,
                    io.github.mangi.eta.data.repository.LocalModelRepository.BUILTIN_GGUF_PROVIDER_ID,
                    io.github.mangi.eta.data.repository.LocalModelRepository.BUILTIN_MNN_PROVIDER_ID)
                LocalSpeechRuntime.releaseForMemory(if (primaryLocal && lowMemory()) ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL else level)
                if (primaryLocal && lowMemory()) {
                    LocalSpeechRuntime.releaseRecognitionForPrimaryModel()
                }
            }, ::lowMemory) { critical ->
                LiteRtLmRuntimeManager.requestMemoryRelief(critical)
                LlamaCppRuntimeManager.requestMemoryRelief(critical)
                MnnRuntimeManager.requestMemoryRelief(critical)
            }
        }
        return checkNotNull(relief)
    }

    fun memoryPressure(context: Context, level: Int) {
        val owner = forContext(context)
        scope.launch {
            try { owner.relieve(level) }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { io.github.mangi.eta.core.AndroidAgentLogger.warn("Nexus model memory relief failed: type=${failure.javaClass.simpleName}") }
        }
    }

    /** Called inside the existing text slot, so an old engine cannot still be generating. */
    suspend fun prepareText(providerId: String) {
        LocalEmbeddingRuntime.releaseCached()
        when (providerId) {
            "litert_lm_builtin" -> { LlamaCppRuntimeManager.stop(); MnnRuntimeManager.stop() }
            "llama_cpp_gguf_builtin" -> { LiteRtLmRuntimeManager.stop(); MnnRuntimeManager.stop() }
            "mnn_builtin" -> { LiteRtLmRuntimeManager.stop(); LlamaCppRuntimeManager.stop() }
        }
    }

    /** Called from an embedding inference slot before loading its native engine. */
    suspend fun prepareEmbedding() {
        LiteRtLmRuntimeManager.stop()
        LlamaCppRuntimeManager.stop()
        MnnRuntimeManager.stop()
    }
}
