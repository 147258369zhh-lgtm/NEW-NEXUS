package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.TencentSpeechConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal fun validateTencentSpeechConfig(config: TencentSpeechConfig, synthesis: Boolean) {
    if (!Regex("[a-z]{2}-[a-z]+(?:-[0-9]+)?").matches(config.region) ||
        !Regex("16k_[A-Za-z_-]{2,40}").matches(config.engine) ||
        (synthesis && (config.voice <= 0 || !config.speed.isFinite() || config.speed !in -2f..6f ||
            !config.volume.isFinite() || config.volume !in -10f..10f))) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请检查腾讯云地域、识别引擎、音色与语速")
    }
}

internal fun validateTencentSpeechKeys(id: String, key: String) {
    if (!Regex("[A-Za-z0-9_-]{1,256}").matches(id.trim()) ||
        !Regex("[A-Za-z0-9_-]{1,256}").matches(key.trim())) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请填写腾讯云 SecretId 和 SecretKey")
    }
}

/** TC3 signing follows the official SDK; secret material never appears in URLs or error messages. */
internal fun tencentSpeechRequest(service: String, action: String, version: String,
    config: TencentSpeechConfig, id: String, key: String, body: JSONObject,
    timestamp: Long = Instant.now().epochSecond): Request {
    require(service == "asr" || service == "tts")
    validateTencentSpeechKeys(id, key)
    validateTencentSpeechConfig(config, service == "tts")
    val host = "$service.tencentcloudapi.com"
    val contentType = "application/json; charset=utf-8"
    val payload = body.toString()
    val date = Instant.ofEpochSecond(timestamp).atOffset(ZoneOffset.UTC).toLocalDate().toString()
    val canonical = "POST\n/\n\ncontent-type:$contentType\nhost:$host\n\ncontent-type;host\n${sha256(payload)}"
    val scope = "$date/$service/tc3_request"
    val signText = "TC3-HMAC-SHA256\n$timestamp\n$scope\n${sha256(canonical)}"
    val dateKey = hmac(("TC3" + key.trim()).toByteArray(), date)
    val serviceKey = hmac(dateKey, service)
    val signature = hmac(hmac(serviceKey, "tc3_request"), signText).hex()
    return Request.Builder().url("https://$host/")
        .header("Authorization", "TC3-HMAC-SHA256 Credential=${id.trim()}/$scope, SignedHeaders=content-type;host, Signature=$signature")
        .header("Host", host).header("X-TC-Action", action).header("X-TC-Version", version)
        .header("X-TC-Timestamp", timestamp.toString()).header("X-TC-Region", config.region)
        .post(payload.toRequestBody(contentType.toMediaType())).build()
}

private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
private fun hmac(key: ByteArray, text: String): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(key, "HmacSHA256")); doFinal(text.toByteArray(Charsets.UTF_8))
}
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun tencentRecognitionBody(config: TencentSpeechConfig, wav: ByteArray): JSONObject {
    if (wav.size <= 44 || wav.size > 44 + 32_000 * 60) {
        throw SpeechFailure(SpeechErrorCode.AUDIO, "腾讯云一句话识别须在60秒以内")
    }
    val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" || String(wav, 8, 8, Charsets.US_ASCII) != "WAVEfmt " ||
        header.getInt(16) != 16 || header.getShort(20).toInt() != 1 || header.getShort(22).toInt() != 1 ||
        header.getInt(24) != 16_000 || header.getInt(28) != 32_000 || header.getShort(32).toInt() != 2 ||
        header.getShort(34).toInt() != 16 || String(wav, 36, 4, Charsets.US_ASCII) != "data" ||
        header.getInt(40) != wav.size - 44 || (wav.size - 44) % 2 != 0) {
        throw SpeechFailure(SpeechErrorCode.AUDIO, "腾讯云识别需要16kHz单声道PCM WAV")
    }
    return JSONObject().put("EngSerViceType", config.engine).put("SourceType", 1).put("VoiceFormat", "wav")
        .put("ProjectId", 0).put("SubServiceType", 2).put("UsrAudioKey", UUID.randomUUID().toString())
        .put("Data", Base64.getEncoder().encodeToString(wav)).put("DataLen", wav.size)
}

internal fun tencentSpeechResponse(body: JSONObject): JSONObject {
    val response = body.optJSONObject("Response") ?: throw SpeechFailure(SpeechErrorCode.PROTOCOL, "腾讯云语音响应格式不完整")
    response.optJSONObject("Error")?.let {
        val code = it.optString("Code")
        val type = when {
            code.startsWith("AuthFailure") || code.startsWith("UnauthorizedOperation") -> SpeechErrorCode.AUTHENTICATION
            code.startsWith("LimitExceeded") || code.startsWith("RequestLimitExceeded") -> SpeechErrorCode.RATE_LIMITED
            code.startsWith("InvalidParameter") || code.startsWith("MissingParameter") -> SpeechErrorCode.CONFIGURATION
            else -> SpeechErrorCode.SERVER
        }
        throw SpeechFailure(type, "腾讯云语音请求失败，请检查凭据、配置及服务额度")
    }
    return response
}

internal suspend fun recognizeTencent(config: TencentSpeechConfig, id: String, key: String, wav: ByteArray): String {
    val response = tencentSpeechResponse(SpeechHttp.json(tencentSpeechRequest("asr", "SentenceRecognition", "2019-06-14",
        config, id, key, tencentRecognitionBody(config, wav))))
    return response.optString("Result").trim().ifEmpty { throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "腾讯云未识别到清晰语音") }
}

internal fun tencentSpeechChunks(text: String): List<String> {
    // Both Chinese and English fit the documented limits. Smaller chunks also bound JSON/base64 audio memory.
    val points = text.codePoints().toArray()
    return points.toList().chunked(80).map { String(it.toIntArray(), 0, it.size) }.filter(String::isNotBlank)
}

internal suspend fun synthesizeTencent(config: TencentSpeechConfig, id: String, key: String, text: String, onAudio: (ByteArray) -> Unit) {
    val chunks = tencentSpeechChunks(text)
    if (chunks.isEmpty()) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有可朗读的文本")
    for (chunk in chunks) {
        val session = UUID.randomUUID().toString()
        val body = JSONObject().put("Text", chunk).put("SessionId", session).put("ModelType", 1)
            .put("VoiceType", config.voice).put("SampleRate", 16000).put("Codec", "pcm")
            .put("Speed", config.speed).put("Volume", config.volume).put("ProjectId", 0)
        val response = tencentSpeechResponse(SpeechHttp.json(tencentSpeechRequest("tts", "TextToVoice", "2019-08-23", config, id, key, body)))
        if (response.optString("SessionId").isNotEmpty() && response.optString("SessionId") != session) {
            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "腾讯云语音返回会话不匹配")
        }
        val pcm = try { Base64.getDecoder().decode(response.optString("Audio")) } catch (_: IllegalArgumentException) {
            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "腾讯云语音音频编码错误")
        }
        if (pcm.isEmpty() || pcm.size % 2 != 0) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "腾讯云语音音频不完整")
        val samples = FloatArray(pcm.size / 2) { i ->
            ((pcm[i * 2].toInt() and 255) or (pcm[i * 2 + 1].toInt() shl 8)).toShort() / 32768f
        }
        val audio = SpeechSegmentAudio(onAudio)
        audio.write(floatPcmToPcm16(StreamingPcmResampler(16000, 24000).process(samples)))
        audio.end(completed = true)
    }
}
