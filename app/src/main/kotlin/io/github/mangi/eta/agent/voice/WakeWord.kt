package io.github.mangi.eta.agent.voice

import android.content.Context
import java.text.Normalizer
import java.util.Locale

internal data class WakeWordConfig(
    val enabled: Boolean = false,
    val words: List<String> = listOf("Nexus"),
    val voiceprintEnabled: Boolean = false,
    val voiceprintThreshold: Float = 0.72f,
) {
    fun sanitized(): WakeWordConfig = copy(
        words = words.map(::normalizeWakeWord).filter { it.length >= 2 }.distinct().take(8),
        voiceprintThreshold = voiceprintThreshold.coerceIn(0.5f, 0.95f),
    )
}

internal object WakeWordMatcher {
    fun matches(transcript: String, config: WakeWordConfig): Boolean {
        if (!config.enabled) return true
        val text = normalizeWakeWord(transcript)
        return config.sanitized().words.any { word ->
            text == word || text.startsWith("$word ") || text.contains(" $word ")
        }
    }
}

internal fun normalizeWakeWord(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()

internal class WakeWordStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("eta_wake_words", Context.MODE_PRIVATE)

    fun read(): WakeWordConfig = WakeWordConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        words = prefs.getStringSet(KEY_WORDS, setOf("Nexus")).orEmpty().toList().sorted(),
        voiceprintEnabled = prefs.getBoolean(KEY_VOICEPRINT, false),
        voiceprintThreshold = prefs.getFloat(KEY_THRESHOLD, 0.72f),
    ).sanitized()

    fun write(config: WakeWordConfig) {
        val value = config.sanitized()
        prefs.edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putStringSet(KEY_WORDS, value.words.toSet())
            .putBoolean(KEY_VOICEPRINT, value.voiceprintEnabled)
            .putFloat(KEY_THRESHOLD, value.voiceprintThreshold)
            .apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_WORDS = "words"
        const val KEY_VOICEPRINT = "voiceprint_enabled"
        const val KEY_THRESHOLD = "voiceprint_threshold"
    }
}
