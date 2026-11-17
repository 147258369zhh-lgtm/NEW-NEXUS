package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.AzureSpeechConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Azure's public regional REST API; resource keys never travel to user supplied hosts. */
internal fun validateAzureSpeechConfig(config: AzureSpeechConfig, synthesis: Boolean) {
    if (!Regex("[a-z][a-z0-9]{1,39}").matches(config.region)) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请填写 Azure 资源所在地域，如 eastasia")
    }
    if (!Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})+").matches(config.locale)) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请填写 Azure 语言代码，如 zh-CN")
    }
    if (synthesis && (!Regex("[A-Za-z0-9_-]{1,100}").matches(config.voice))) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请填写 Azure 音色名称，如 zh-CN-XiaoxiaoNeural")
    }
}

internal fun azureRecognitionRequest(config: AzureSpeechConfig, key: String, wav: ByteArray): Request {
    validateAzureSpeechConfig(config, synthesis = false)
    validateAzureSpeechKey(key)
    // SpeechRecorder creates canonical PCM16, mono, 16 kHz WAV. This REST API caps input at 60 s.
    if (wav.size <= 44 || wav.size > 44 + 16_000 * 2 * 60) {
        throw SpeechFailure(SpeechErrorCode.AUDIO, "Azure 短音频识别须在 60 秒以内")
    }
    val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" ||
        String(wav, 8, 8, Charsets.US_ASCII) != "WAVEfmt " ||
        header.getInt(16) != 16 || header.getShort(20).toInt() != 1 ||
        header.getShort(22).toInt() != 1 || header.getInt(24) != 16_000 ||
        header.getInt(28) != 32_000 || header.getShort(32).toInt() != 2 ||
        header.getShort(34).toInt() != 16 || String(wav, 36, 4, Charsets.US_ASCII) != "data" ||
        header.getInt(40) != wav.size - 44 || (wav.size - 44) % 2 != 0
    ) throw SpeechFailure(SpeechErrorCode.AUDIO, "Azure 识别需要 16 kHz 单声道 PCM WAV")
    val url = ("https://${config.region}.stt.speech.microsoft.com" +
        "/speech/recognition/conversation/cognitiveservices/v1").toHttpUrl().newBuilder()
        .addQueryParameter("language", config.locale).addQueryParameter("format", "simple").build()
    return Request.Builder().url(url).header("Ocp-Apim-Subscription-Key", key.trim())
        .post(wav.toRequestBody("audio/wav; codecs=\"audio/pcm\"; samplerate=16000".toMediaType())).build()
}

internal suspend fun recognizeAzure(config: AzureSpeechConfig, key: String, wav: ByteArray): String =
    azureTranscript(SpeechHttp.json(azureRecognitionRequest(config, key, wav)))

internal fun azureTranscript(response: JSONObject): String = when (response.optString("RecognitionStatus")) {
    "Success" -> response.optString("DisplayText").trim().ifEmpty {
        throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到语音，请重试")
    }
    "NoMatch", "InitialSilenceTimeout", "BabbleTimeout" ->
        throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到清晰语音，请重试")
    "Error" -> throw SpeechFailure(SpeechErrorCode.SERVER, "Azure 识别失败，请检查服务资源和地域")
    else -> throw SpeechFailure(SpeechErrorCode.PROTOCOL, "Azure 返回未知识别状态")
}

private fun xmlText(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
    .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

internal fun azureSynthesisRequest(config: AzureSpeechConfig, key: String, text: String): Request {
    validateAzureSpeechConfig(config, synthesis = true)
    validateAzureSpeechKey(key)
    val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='${config.locale}'>" +
        "<voice name='${config.voice}'>${xmlText(text)}</voice></speak>"
    return Request.Builder().url("https://${config.region}.tts.speech.microsoft.com/cognitiveservices/v1")
        .header("Ocp-Apim-Subscription-Key", key.trim())
        .header("X-Microsoft-OutputFormat", "raw-24khz-16bit-mono-pcm")
        .header("User-Agent", "NEXA")
        .post(ssml.toRequestBody("application/ssml+xml; charset=utf-8".toMediaType())).build()
}

internal fun validateAzureSpeechKey(key: String) {
    if (!Regex("[A-Za-z0-9_-]{1,256}").matches(key.trim())) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请填写有效的 Azure 资源密钥")
    }
}

internal suspend fun synthesizeAzure(config: AzureSpeechConfig, key: String, text: String, onAudio: (ByteArray) -> Unit) {
    SpeechHttp.read(azureSynthesisRequest(config, key, text)) { response ->
        val contentType = response.body.contentType()?.toString().orEmpty()
        if (contentType.contains("json", ignoreCase = true) || contentType.contains("xml", ignoreCase = true)) {
            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "Azure 未返回 PCM 音频")
        }
        val source = response.body.source()
        val audio = SpeechSegmentAudio(onAudio)
        while (!source.exhausted()) audio.write(source.readByteArray(minOf(8192L, source.buffer.size)))
        audio.end(completed = true)
    }
}
