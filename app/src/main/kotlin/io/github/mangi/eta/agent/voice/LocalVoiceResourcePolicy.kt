package io.github.mangi.eta.agent.voice

import android.content.ComponentCallbacks2

/**
 * 本地语音三套件的保活优先级。
 *
 * 文本主模型承担整个对话，最不该被语音组件挤掉；本地识别决定用户还能不能继续说；
 * 播报可以随时重新合成，因此最先释放。这里只给出“该放谁”的顺序，
 * 真正释放仍走 [LocalSpeechRuntime.release]，不会触碰文本模型。
 */
@Suppress("DEPRECATION")
internal object LocalVoiceResourcePolicy {
    /** 释放顺序：先播报，再识别。文本主模型不在其中。 */
    val evictionOrder: List<LocalSpeechKind> = listOf(LocalSpeechKind.TTS, LocalSpeechKind.ASR)

    /**
     * 按系统内存压力决定这一轮要释放哪些语音模型。
 * 普通低内存先释放空闲的 TTS；临界内存即使正在朗读也先停播并释放 TTS。ASR 只在临界内存
 * 且不在聆听时释放。文本主模型始终不在此策略中。
     */
    fun releaseTargets(level: Int, speaking: Boolean, listening: Boolean): List<LocalSpeechKind> {
        val critical = when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            -> true
            else -> false
        }
        val low = critical || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        if (!low) return emptyList()
        return buildList {
            if (critical || !speaking) add(LocalSpeechKind.TTS)
            // 识别只在真正吃紧时让位，日常低内存先留下它，用户随时能接着说。
            if (critical && !listening) add(LocalSpeechKind.ASR)
        }
    }
}
