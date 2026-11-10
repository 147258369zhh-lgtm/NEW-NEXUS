package io.github.mangi.eta.agent.tool

import android.content.Context
import android.os.Environment
import androidx.core.content.FileProvider
import io.github.mangi.eta.agent.voice.pcmToWav
import io.github.mangi.eta.agent.voice.synthesizeSpeech
import io.github.mangi.eta.agent.voice.validateSpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** 在 Agent 权限边界内把当前 TTS 配置转换为可以继续分享或播放的 WAV 文件。 */
internal object VoiceSynthesisTool {
    private const val SAMPLE_RATE = 24_000
    private const val MAX_PCM_BYTES = 60 * 1024 * 1024
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())

    fun execute(context: Context, args: JSONObject): JSONObject = runBlocking {
        val text = args.optString("text").trim()
        require(text.isNotEmpty()) { "text 不能为空" }
        require(text.length <= 10_000) { "单次语音合成最多 10,000 字" }
        val base = SpeechSettingsRepository.settings()
        require(base.tts != TtsProvider.NONE) { "请先在语音合成设置中选择服务" }
        val voice = args.optString("voice").trim().take(160)
        val language = args.optString("language").trim().take(32)
        val speed = args.optDouble("speed", base.localTtsRate.toDouble()).toFloat().coerceIn(0.5f, 2f)
        val settings = when (base.tts) {
            TtsProvider.LOCAL -> base.copy(
                localTtsVoiceId = voice.ifBlank { base.localTtsVoiceId },
                localTtsLocale = language.ifBlank { base.localTtsLocale },
                localTtsRate = speed,
            )
            TtsProvider.QWEN -> if (voice.isBlank()) base else base.copy(qwenVoice = voice)
            TtsProvider.DOUBAO -> if (voice.isBlank()) base else base.copy(doubaoVoice = voice)
            TtsProvider.GEMINI -> if (voice.isBlank()) base else base.copy(geminiTts = base.geminiTts.copy(voice = voice))
            TtsProvider.MINIMAX -> base.copy(minimaxTts = base.minimaxTts.copy(
                voice = voice.ifBlank { base.minimaxTts.voice },
                speed = speed,
            ))
            TtsProvider.NONE -> base
        }
        val credentials = SpeechSettingsRepository.credentials(context)
        validateSpeechSettings(settings, credentials, synthesis = true)
        val pcm = ByteArrayOutputStream()
        synthesizeSpeech(settings, credentials, text) { chunk ->
            if (pcm.size() + chunk.size > MAX_PCM_BYTES) {
                throw IllegalArgumentException("合成音频超过 60 MB，请缩短文本")
            }
            pcm.write(chunk)
        }
        require(pcm.size() > 0) { "语音服务没有返回音频" }
        val directory = audioDirectory(context).apply { mkdirs() }
        require(directory.isDirectory || directory.mkdirs()) { "无法创建音频目录" }
        val file = uniqueFile(directory, outputFileName(args.optString("output_name"), text))
        val temporary = File(directory, ".${file.name}.tmp")
        temporary.writeBytes(pcmToWav(pcm.toByteArray(), SAMPLE_RATE))
        check(temporary.renameTo(file)) { "音频文件保存失败" }
        JSONObject()
            .put("ok", true)
            .put("tool", "voice_synthesize")
            .put("path", file.absolutePath)
            .put("content_uri", FileProvider.getUriForFile(
                context,
                "${context.packageName}.files",
                file,
            ).toString())
            .put("mime_type", "audio/wav")
            .put("bytes", file.length())
            .put("duration_seconds", pcm.size().toDouble() / (SAMPLE_RATE * 2))
            .put("sample_rate_hz", SAMPLE_RATE)
            .put("provider", settings.tts.name.lowercase(Locale.ROOT))
            .put("voice", effectiveVoice(settings))
            .put("language", effectiveLanguage(settings))
            .put("speed", speed)
    }

    private fun effectiveVoice(settings: io.github.mangi.eta.data.model.SpeechSettings): String = when (settings.tts) {
        TtsProvider.LOCAL -> settings.localTtsVoiceId
        TtsProvider.QWEN -> settings.qwenVoice
        TtsProvider.DOUBAO -> settings.doubaoVoice
        TtsProvider.GEMINI -> settings.geminiTts.voice
        TtsProvider.MINIMAX -> settings.minimaxTts.voice
        TtsProvider.NONE -> ""
    }

    private fun effectiveLanguage(settings: io.github.mangi.eta.data.model.SpeechSettings): String =
        if (settings.tts == TtsProvider.LOCAL) settings.localTtsLocale else settings.language

    private fun audioDirectory(context: Context): File =
        (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir)
            .resolve("Nexus Voice")

    internal fun outputFileName(requested: String, text: String): String {
        val cleaned = requested.trim()
            .substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "-")
            .trim(' ', '.')
            .removeSuffix(".wav")
            .take(64)
            .ifBlank { "voice-${stamp.format(Instant.now())}-${text.take(12).replace(Regex("\\s+"), "-")}" }
        return "$cleaned.wav"
    }

    private fun uniqueFile(directory: File, name: String): File {
        val first = File(directory, name)
        if (!first.exists()) return first
        val stem = name.removeSuffix(".wav")
        return generateSequence(2) { it + 1 }
            .map { File(directory, "$stem-$it.wav") }
            .first { !it.exists() }
    }
}
