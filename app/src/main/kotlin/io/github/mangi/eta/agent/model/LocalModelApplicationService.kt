package io.github.mangi.eta.agent.model

import android.content.Context
import io.github.mangi.eta.agent.runtime.LocalInferenceScheduling
import io.github.mangi.eta.agent.voice.LocalSpeechRuntime
import io.github.mangi.eta.agent.voice.localSpeechKind
import io.github.mangi.eta.agent.voice.LocalSpeechKind
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Existing UI and startup entry points share the native owners and the same generation slot. */
internal class LocalModelApplicationService {
    val speechStates get() = LocalSpeechRuntime.states
    val embeddingState get() = LocalEmbeddingRuntime.state

    suspend fun startEmbedding(record: LocalModelRecord) =
        LocalEmbeddingRuntime.warmUp(SettingsDataStore.applicationContext(), record)

    suspend fun embed(recordId: String, text: String): LocalEmbeddingResult {
        val record = LocalModelRepository.record(recordId) ?: throw IllegalArgumentException("本地向量模型记录不存在")
        return LocalEmbeddingRuntime.embed(SettingsDataStore.applicationContext(), record, text)
    }

    suspend fun stopEmbedding(recordId: String) = LocalEmbeddingRuntime.stop(recordId)

    suspend fun startText(record: LocalModelRecord, automatic: Boolean = false): LocalModelRuntimeSnapshot {
        val before = SettingsDataStore.settings().let { it.selectedProviderId to it.selectedModelId }
        LocalModelRepository.activateBuiltInRuntime(record.id)
        val result = LocalInferenceScheduling.warmUp(record.id, automatic) {
            LocalModelResourceOwner.prepareText(when (record.format) {
                LocalModelFormat.LITERT_LM -> "litert_lm_builtin"
                LocalModelFormat.GGUF -> "llama_cpp_gguf_builtin"
                else -> "mnn_builtin"
            })
            val launch = if (automatic) NativeLaunchKind.AUTOMATIC else NativeLaunchKind.USER
            when (record.format) {
                LocalModelFormat.LITERT_LM -> LiteRtLmRuntimeManager.warmUp(record.id, launch)
                LocalModelFormat.GGUF -> LlamaCppRuntimeManager.warmUp(record.id, launch)
                LocalModelFormat.MNN -> MnnRuntimeManager.warmUp(record.id, launch)
                else -> error("LOCAL_TEXT_FORMAT_UNSUPPORTED")
            }
        }
        // A later explicit model selection wins over this delayed startup result.
        val now = SettingsDataStore.settings().let { it.selectedProviderId to it.selectedModelId }
        if (now == before) LocalModelRepository.selectBuiltInRuntime(record.id)
        return result
    }

    suspend fun startSpeech(record: LocalModelRecord) {
        val kind = requireNotNull(localSpeechKind(record)) { "LOCAL_SPEECH_FORMAT_UNSUPPORTED" }
        val before = SpeechSettingsRepository.settings()
        val next = when (kind) {
            LocalSpeechKind.TTS -> before.copy(tts = TtsProvider.LOCAL, localTtsModelId = record.id,
                localTtsVoiceId = before.localTtsVoiceId.takeIf { before.localTtsModelId == record.id }.orEmpty(),
                localTtsLocale = before.localTtsLocale.takeIf { before.localTtsModelId == record.id }.orEmpty())
            LocalSpeechKind.ASR -> before.copy(asr = AsrProvider.LOCAL, localAsrModelId = record.id)
        }
        LocalSpeechRuntime.warmUp(next, kind)
        if (SpeechSettingsRepository.settings() == before) SpeechSettingsRepository.save(next)
    }

    suspend fun stop(record: LocalModelRecord) {
        if ("keyword" in record.outputModalities) {
            io.github.mangi.eta.agent.voice.BackgroundWakeWordRuntime.stopModel(SettingsDataStore.applicationContext(), record.id); return
        }
        if ("speaker_embedding" in record.outputModalities) { io.github.mangi.eta.agent.voice.LocalSpeakerModel.release(record.id); return }
        if ("embedding" in record.outputModalities) return LocalEmbeddingRuntime.stop(record.id)
        if (localSpeechKind(record) != null) return LocalSpeechRuntime.stop(record.id)
        LocalInferenceScheduling.stopModel(record.id).use {
            withContext(Dispatchers.IO) {
                withTimeout(5_000) {
                    when (record.format) {
                        LocalModelFormat.LITERT_LM -> LiteRtLmRuntimeManager.stop(record.id)
                        LocalModelFormat.GGUF -> LlamaCppRuntimeManager.stop(record.id)
                        LocalModelFormat.MNN -> MnnRuntimeManager.stop(record.id)
                        else -> Unit
                    }
                }
            }
        }
    }

    suspend fun delete(context: Context, record: LocalModelRecord) {
        if (record.outputModalities.any { it in setOf("keyword", "speaker_embedding", "embedding") }) stop(record)
        suspend fun removeRecord() {
            val wakeStore = io.github.mangi.eta.agent.voice.WakeWordStore(context)
            val wake = wakeStore.read()
            if (wake.modelId == record.id || wake.speakerModelId == record.id) wakeStore.write(wake.copy(
                enabled = if (wake.modelId == record.id) false else wake.enabled,
                modelId = if (wake.modelId == record.id) "" else wake.modelId,
                speakerModelId = if (wake.speakerModelId == record.id) "" else wake.speakerModelId,
                voiceprintEnabled = if (wake.speakerModelId == record.id) false else wake.voiceprintEnabled))
            val speech = SpeechSettingsRepository.settings()
            if (speech.localTtsModelId == record.id || speech.localAsrModelId == record.id) {
                val removingAsr = speech.localAsrModelId == record.id
                SpeechSettingsRepository.save(speech.copy(
                    tts = if (speech.localTtsModelId == record.id) TtsProvider.NONE else speech.tts,
                    recognitionEnabled = if (removingAsr) false else speech.recognitionEnabled,
                    localTtsModelId = if (speech.localTtsModelId == record.id) "" else speech.localTtsModelId,
                    localAsrModelId = if (removingAsr) "" else speech.localAsrModelId))
            }
            LocalModelRepository.delete(context, record.id)
        }
        if ("embedding" in record.outputModalities) LocalInferenceScheduling.stopModel(record.id).use {
            LocalEmbeddingRuntime.stop(record.id)
            removeRecord()
        }
        else if ("speaker_embedding" in record.outputModalities) io.github.mangi.eta.agent.voice.LocalSpeakerModel.whileStopped(record.id) { removeRecord() }
        else if (localSpeechKind(record) != null) LocalSpeechRuntime.whileStopped(record.id) { removeRecord() }
        else LocalInferenceScheduling.stopModel(record.id).use { stop(record); removeRecord() }
    }
}
