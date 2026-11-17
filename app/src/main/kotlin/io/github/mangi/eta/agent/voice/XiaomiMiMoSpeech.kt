package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.XiaomiMiMoSpeechConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Request

/** Public pay-as-you-go API, independent of the enterprise XiaoAI AIVS SDK.
 * Sources: https://mimo.mi.com/static/docs/api/audio/Speech-Recognition-post.md
 * https://mimo.mi.com/static/docs/api/audio/tts-post.md
 * https://mimo.mi.com/static/docs/quick-start/usage-guide/audio/speech-synthesis-v2.5.md
 * The official PCM example specifies 24 kHz PCM16LE mono.
 */
private const val XIAOMI_MIMO_SPEECH_URL = "https://api.xiaomimimo.com/v1/chat/completions"
private const val XIAOMI_MIMO_MAX_BASE64_BYTES = 10_000_000L
private const val XIAOMI_MIMO_TTS_CHUNK_BYTES = 512
private const val XIAOMI_MIMO_TTS_CHUNK_POINTS = 240

internal fun validateXiaomiMiMoConfig(config: XiaomiMiMoSpeechConfig, synthesis: Boolean) {
    if (!synthesis && config.language !in setOf("auto", "zh", "en")) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "小米 MiMo 识别语言须为 auto、zh 或 en")
    }
    if (synthesis && config.voice !in setOf("mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean")) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请选择小米 MiMo 支持的内置音色")
    }
}

internal fun validateXiaomiMiMoKey(key: String) {
    // Subscription tp-/ttp- keys use a separate endpoint and cannot be mixed with this host.
    if (!Regex("sk-[A-Za-z0-9_-]{1,512}").matches(key.trim())) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请填写小米 MiMo 国内按量 API Key（sk- 开头），不支持 Token Plan 密钥")
    }
}

/** The app captures canonical mono PCM16 WAV. The vendor caps encoded input at 10 MB,
 * rather than specifying a microphone streaming protocol or an audio duration limit.
 */
internal fun xiaomiMiMoRecognitionRequest(config: XiaomiMiMoSpeechConfig, key: String, wav: ByteArray): Request {
    validateXiaomiMiMoConfig(config, synthesis = false)
    validateXiaomiMiMoKey(key)
    if (wav.size <= 44 || ((wav.size.toLong() + 2) / 3) * 4 > XIAOMI_MIMO_MAX_BASE64_BYTES) {
        throw SpeechFailure(SpeechErrorCode.AUDIO, "小米 MiMo 识别音频为空或超过 10 MB Base64 输入限制")
    }
    val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    val rate = header.getInt(24)
    if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" ||
        header.getInt(4) != wav.size - 8 || String(wav, 8, 8, Charsets.US_ASCII) != "WAVEfmt " ||
        header.getInt(16) != 16 || header.getShort(20).toInt() != 1 ||
        header.getShort(22).toInt() != 1 || rate <= 0 ||
        header.getInt(28).toLong() != rate.toLong() * 2 || header.getShort(32).toInt() != 2 ||
        header.getShort(34).toInt() != 16 || String(wav, 36, 4, Charsets.US_ASCII) != "data" ||
        header.getInt(40) != wav.size - 44 || (wav.size - 44) % 2 != 0) {
        throw SpeechFailure(SpeechErrorCode.AUDIO, "小米 MiMo 识别需要有效的单声道 PCM WAV")
    }
    val input = JSONObject().put("type", "input_audio").put("input_audio", JSONObject()
        .put("data", Base64.getEncoder().encodeToString(wav)).put("format", "wav"))
    val body = JSONObject().put("model", "mimo-v2.5-asr")
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", JSONArray().put(input))))
        .put("asr_options", JSONObject().put("language", config.language)).put("stream", false)
    return SpeechHttp.jsonRequest(XIAOMI_MIMO_SPEECH_URL, key, body).build()
}

internal suspend fun recognizeXiaomiMiMo(config: XiaomiMiMoSpeechConfig, key: String, wav: ByteArray): String =
    xiaomiMiMoTranscript(SpeechHttp.json(xiaomiMiMoRecognitionRequest(config, key, wav)))

internal fun xiaomiMiMoTranscript(response: JSONObject): String {
    if (response.has("error")) throw xiaomiMiMoServiceFailure(response.optJSONObject("error"))
    val choice = response.optJSONArray("choices")?.optJSONObject(0)
        ?: throw SpeechFailure(SpeechErrorCode.PROTOCOL, "小米 MiMo 未返回识别结果")
    xiaomiMiMoFinishReason(choice.opt("finish_reason"), required = true)
    val text = choice.optJSONObject("message")?.opt("content") as? String
        ?: throw SpeechFailure(SpeechErrorCode.PROTOCOL, "小米 MiMo 识别结果格式无效")
    return text.trim().ifEmpty { throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到清晰语音，请重试") }
}

/** This is a local request-size policy, not a vendor-published text limit.
 * Keep segments short so each response fits the shared PCM and SSE memory bounds.
 */
internal fun xiaomiMiMoSpeechChunks(text: String): List<String> {
    if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "播报文本不能为空")
    val chunks = mutableListOf<String>()
    var start = 0
    var offset = 0
    var bytes = 0
    var points = 0
    while (offset < text.length) {
        val next = offset + Character.charCount(text.codePointAt(offset))
        val count = text.substring(offset, next).toByteArray(Charsets.UTF_8).size
        if (bytes + count > XIAOMI_MIMO_TTS_CHUNK_BYTES || points == XIAOMI_MIMO_TTS_CHUNK_POINTS) {
            chunks += text.substring(start, offset)
            start = offset
            bytes = 0
            points = 0
        }
        bytes += count
        points++
        offset = next
    }
    chunks += text.substring(start)
    return chunks
}

internal fun xiaomiMiMoSynthesisRequest(config: XiaomiMiMoSpeechConfig, key: String, text: String): Request {
    validateXiaomiMiMoConfig(config, synthesis = true)
    validateXiaomiMiMoKey(key)
    if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > XIAOMI_MIMO_TTS_CHUNK_BYTES ||
        text.codePointCount(0, text.length) > XIAOMI_MIMO_TTS_CHUNK_POINTS) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "小米 MiMo 单段播报文本为空或过长")
    }
    val body = JSONObject().put("model", "mimo-v2.5-tts")
        .put("messages", JSONArray().put(JSONObject().put("role", "assistant").put("content", text)))
        .put("audio", JSONObject().put("format", "pcm16").put("voice", config.voice)).put("stream", true)
    return SpeechHttp.jsonRequest(XIAOMI_MIMO_SPEECH_URL, key, body)
        .header("Accept", "text/event-stream").build()
}

internal suspend fun synthesizeXiaomiMiMo(
    config: XiaomiMiMoSpeechConfig, key: String, text: String, onAudio: (ByteArray) -> Unit,
) {
    validateXiaomiMiMoConfig(config, synthesis = true)
    validateXiaomiMiMoKey(key)
    for (chunk in xiaomiMiMoSpeechChunks(text)) {
        if (chunk.isBlank()) continue
        SpeechHttp.read(xiaomiMiMoSynthesisRequest(config, key, chunk)) { response ->
            val decoder = XiaomiMiMoSpeechDecoder(onAudio)
            readSpeechEvents(response) { _, data -> decoder.event(data) }
            decoder.end()
        }
    }
}

/** SSE boundaries are independent of PCM sample boundaries. stop, not EOF or [DONE],
 * confirms successful completion; the shared decoder validates PCM length and size.
 */
internal class XiaomiMiMoSpeechDecoder(onAudio: (ByteArray) -> Unit) {
    private val audio = SpeechSegmentAudio(onAudio)
    private var completed = false

    fun event(value: String): Boolean {
        if (completed) return false
        if (value == "[DONE]") return false
        val message = JSONObject(value)
        if (message.has("error")) throw xiaomiMiMoServiceFailure(message.optJSONObject("error"))
        val choices = message.optJSONArray("choices")
            ?: throw SpeechFailure(SpeechErrorCode.PROTOCOL, "小米 MiMo 未返回合成状态")
        // OpenAI-compatible streams may include a final usage event with no choices.
        if (choices.length() == 0) return true
        val choice = choices.optJSONObject(0)
            ?: throw SpeechFailure(SpeechErrorCode.PROTOCOL, "小米 MiMo 合成状态格式无效")
        val delta = choice.optJSONObject("delta")
            ?: throw SpeechFailure(SpeechErrorCode.PROTOCOL, "小米 MiMo 未返回所请求的流式 PCM 音频")
        val rawAudio = delta.opt("audio")
        if (rawAudio != null && rawAudio != JSONObject.NULL && rawAudio !is JSONObject) {
            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "小米 MiMo 音频对象格式无效")
        }
        val audioObject = delta.optJSONObject("audio")
        if (audioObject != null && audioObject.has("data")) {
            val encoded = audioObject.opt("data") as? String
                ?: throw SpeechFailure(SpeechErrorCode.PROTOCOL, "小米 MiMo 音频编码格式无效")
            if (encoded.isNotEmpty()) {
                if (encoded.length.toLong() > SpeechHttp.MAX_JSON_BYTES) {
                    throw SpeechFailure(SpeechErrorCode.PROTOCOL, "小米 MiMo 音频块过大")
                }
                val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) {
                    throw SpeechFailure(SpeechErrorCode.PROTOCOL, "小米 MiMo 音频编码无效")
                }
                audio.write(bytes)
            }
        }
        completed = xiaomiMiMoFinishReason(choice.opt("finish_reason"), required = false)
        return !completed
    }

    fun end() = audio.end(completed)
}

private fun xiaomiMiMoFinishReason(reason: Any?, required: Boolean): Boolean = when (reason) {
    "stop" -> true
    "length" -> throw SpeechFailure(SpeechErrorCode.SERVER, "小米 MiMo 输出超出限制，请缩短内容后重试")
    "content_filter" -> throw SpeechFailure(SpeechErrorCode.SERVER, "小米 MiMo 未完成语音处理，请检查内容后重试")
    null, JSONObject.NULL -> if (!required) false else throw SpeechFailure(SpeechErrorCode.PROTOCOL, "小米 MiMo 未确认识别完成")
    else -> throw SpeechFailure(SpeechErrorCode.PROTOCOL, "小米 MiMo 返回未知完成状态")
}

private fun xiaomiMiMoServiceFailure(error: JSONObject?): SpeechFailure = when (error?.optInt("code", -1)) {
    401, 403 -> SpeechFailure(SpeechErrorCode.AUTHENTICATION, "小米 MiMo 认证失败，请检查国内按量 API Key 和服务权限")
    402 -> SpeechFailure(SpeechErrorCode.RATE_LIMITED, "小米 MiMo 账户余额不足，请检查账户余额")
    429 -> SpeechFailure(SpeechErrorCode.RATE_LIMITED, "小米 MiMo 请求过于频繁，请稍后重试")
    else -> SpeechFailure(SpeechErrorCode.SERVER, "小米 MiMo 语音服务失败，请检查配置和服务权限")
}
