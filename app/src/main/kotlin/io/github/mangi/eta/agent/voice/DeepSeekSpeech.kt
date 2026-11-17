package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.cloud.CloudMediaProtocol
import io.github.mangi.eta.data.model.DeepSeekSpeechConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject

private const val DEEPSEEK_ASR_MAX_BYTES = 8 * 1024 * 1024
private const val DEEPSEEK_TTS_MAX_BYTES = 60L * 1024 * 1024

internal fun validateDeepSeekSpeechConfig(config: DeepSeekSpeechConfig, synthesis: Boolean) {
    speechBaseUrl(config.baseUrl)
    if (synthesis && config.voice !in setOf("stella", "tide")) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "DeepSeek 音色仅支持 stella 或 tide")
    }
}

internal fun validateDeepSeekSpeechKey(key: String) {
    val normalized = key.trim()
    if (normalized.isEmpty() || normalized.length > 512 || normalized.any { it.code !in 33..126 }) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请填写 DeepSeek 语音专用 API Key")
    }
}

/** Microphone recording remains local until the user finishes; ASR is one complete WAV upload. */
internal fun deepSeekRecognitionRequest(config: DeepSeekSpeechConfig, key: String, wav: ByteArray): Request {
    validateDeepSeekSpeechConfig(config, synthesis = false)
    validateDeepSeekSpeechKey(key)
    validateDeepSeekSpeechWav(wav)
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType())).build()
    return Request.Builder().url(deepSeekSpeechEndpoint(config, "asr"))
        .header("Authorization", "Bearer ${key.trim()}")
        .post(deepSeekOneShot(body)).build()
}

internal suspend fun recognizeDeepSeek(config: DeepSeekSpeechConfig, key: String, wav: ByteArray): String =
    deepSeekTranscript(SpeechHttp.json(deepSeekRecognitionRequest(config, key, wav)))

internal fun deepSeekTranscript(response: JSONObject): String {
    if (response.has("error") && !response.isNull("error")) {
        throw SpeechFailure(SpeechErrorCode.SERVER, "DeepSeek 识别失败，请检查凭据、额度与服务权限")
    }
    val text = response.opt("text") as? String
        ?: throw SpeechFailure(SpeechErrorCode.PROTOCOL, "DeepSeek 识别结果格式无效")
    return text.trim().ifEmpty { throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到清晰语音，请重试") }
}

internal fun deepSeekSynthesisRequest(config: DeepSeekSpeechConfig, key: String, text: String): Request {
    validateDeepSeekSpeechConfig(config, synthesis = true)
    validateDeepSeekSpeechKey(key)
    val body = try {
        CloudMediaProtocol.speech(JSONObject().put("text", text).put("voice_id", config.voice))
    } catch (_: IllegalArgumentException) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "DeepSeek 播报文本为空或超过 10,000 计费字符，汉字按两个字符计")
    }
    return Request.Builder().url(deepSeekSpeechEndpoint(config, "tts"))
        .header("Authorization", "Bearer ${key.trim()}").header("Accept", "audio/wav")
        .post(deepSeekOneShot(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))).build()
}

internal suspend fun synthesizeDeepSeek(
    config: DeepSeekSpeechConfig, key: String, text: String, onAudio: (ByteArray) -> Unit,
) {
    SpeechHttp.read(deepSeekSynthesisRequest(config, key, text)) { response ->
        val source = response.body.source()
        val contentType = response.body.contentType()?.toString().orEmpty()
        if (contentType.contains("json", ignoreCase = true) || !source.request(12) ||
            source.buffer.peek().run { readUtf8(4) != "RIFF" || run { skip(4); readUtf8(4) != "WAVE" } }
        ) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "DeepSeek 未返回有效 WAV 音频")
        val audio = SpeechSegmentAudio(onAudio, limit = DEEPSEEK_TTS_MAX_BYTES)
        while (!source.exhausted()) audio.write(source.readByteArray(minOf(8192L, source.buffer.size)))
        audio.end(completed = true)
    }
}

private fun deepSeekSpeechEndpoint(config: DeepSeekSpeechConfig, operation: String): String =
    speechBaseUrl(config.baseUrl).removeSuffix("/v1") + "/v1/tools/$operation"

/** One-shot bodies prevent HTTP follow-up replay of a potentially billed request. */
private fun deepSeekOneShot(body: RequestBody): RequestBody = object : RequestBody() {
    override fun contentType() = body.contentType()
    override fun contentLength() = body.contentLength()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
}

internal fun validateDeepSeekSpeechWav(wav: ByteArray) {
    fun invalid(): Nothing = throw SpeechFailure(SpeechErrorCode.AUDIO, "DeepSeek 识别需要 120 秒以内、最大 8 MiB 的 16 位 PCM WAV")
    if (wav.size <= 44 || wav.size > DEEPSEEK_ASR_MAX_BYTES) invalid()
    val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    val rate = header.getInt(24)
    if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" || header.getInt(4) != wav.size - 8 ||
        String(wav, 8, 8, Charsets.US_ASCII) != "WAVEfmt " || header.getInt(16) != 16 ||
        header.getShort(20).toInt() != 1 || header.getShort(22).toInt() != 1 ||
        rate !in setOf(8000, 16000, 22050, 24000, 32000, 44100, 48000) ||
        header.getInt(28) != rate * 2 || header.getShort(32).toInt() != 2 ||
        header.getShort(34).toInt() != 16 || String(wav, 36, 4, Charsets.US_ASCII) != "data" ||
        header.getInt(40) != wav.size - 44 || (wav.size - 44) % 2 != 0 ||
        wav.size - 44 > rate.toLong() * 2 * 120
    ) invalid()
}
