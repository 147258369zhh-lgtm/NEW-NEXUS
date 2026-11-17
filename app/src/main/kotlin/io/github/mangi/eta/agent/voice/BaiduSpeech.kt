package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.BaiduSpeechConfig
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Contract: https://github.com/Baidu-AIP/speech-demo/tree/master/rest-api-asr
 * and https://github.com/Baidu-AIP/speech-demo/tree/master/rest-api-tts.
 * Fixed HTTPS hosts; API keys, secrets and access tokens are only sent in POST bodies.
 */
internal fun validateBaiduSpeechConfig(config: BaiduSpeechConfig, synthesis: Boolean) {
    if (!synthesis && config.devPid !in setOf(1537, 80001)) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "百度识别当前支持普通话标准版 1537 或极速版 80001")
    }
    if (synthesis && (config.voice !in setOf(0, 1, 3, 4, 5, 103, 106, 110, 111) ||
            config.speed !in 0..15 || config.pitch !in 0..15 || config.volume !in 0..9)) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请检查百度音色、语速（0～15）、音调（0～15）和音量（0～9）")
    }
}

internal fun validateBaiduSpeechCredentials(key: String, secret: String) {
    val pattern = Regex("[A-Za-z0-9_-]{1,256}")
    if (!pattern.matches(key.trim()) || !pattern.matches(secret.trim())) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请填写有效的百度 API Key 和 Secret Key")
    }
}

internal fun baiduTokenRequest(key: String, secret: String): Request {
    validateBaiduSpeechCredentials(key, secret)
    return Request.Builder().url("https://aip.baidubce.com/oauth/2.0/token")
        .post(FormBody.Builder().add("grant_type", "client_credentials")
            .add("client_id", key.trim()).add("client_secret", secret.trim()).build()).build()
}

internal fun baiduAccessToken(response: JSONObject, requiredScope: String): String {
    if (response.has("error")) {
        throw SpeechFailure(SpeechErrorCode.AUTHENTICATION, "百度认证失败，请检查 API Key 和 Secret Key")
    }
    val token = response.optString("access_token")
    if (token.isBlank() || token.length > 4096 || token.any { it.isISOControl() }) {
        throw SpeechFailure(SpeechErrorCode.PROTOCOL, "百度未返回有效访问令牌")
    }
    val scopes = response.optString("scope").split(Regex("\\s+"))
    if (requiredScope !in scopes) {
        throw SpeechFailure(SpeechErrorCode.AUTHENTICATION, "百度应用未开通所选语音服务，请检查服务权限")
    }
    return token
}

/** Only the recorder's canonical PCM16 mono 16 kHz WAV is accepted, with a 60 second cap. */
internal fun baiduRecognitionRequest(config: BaiduSpeechConfig, token: String, wav: ByteArray): Request {
    validateBaiduSpeechConfig(config, synthesis = false)
    val pcm = baiduRecognitionPcm(wav)
    val body = JSONObject().put("dev_pid", config.devPid).put("format", "pcm")
        .put("rate", 16000).put("channel", 1).put("token", token)
        .put("cuid", UUID.randomUUID().toString()).put("len", pcm.size)
        .put("speech", Base64.getEncoder().encodeToString(pcm))
    val path = if (config.devPid == 80001) "pro_api" else "server_api"
    return Request.Builder().url("https://vop.baidu.com/$path")
        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
}

private fun baiduRecognitionPcm(wav: ByteArray): ByteArray {
    if (wav.size <= 44 || wav.size > 44 + 32_000 * 60) {
        throw SpeechFailure(SpeechErrorCode.AUDIO, "百度短语音识别须在 60 秒以内")
    }
    val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" ||
        String(wav, 8, 8, Charsets.US_ASCII) != "WAVEfmt " ||
        header.getInt(4) != wav.size - 8 || header.getInt(16) != 16 ||
        header.getShort(20).toInt() != 1 || header.getShort(22).toInt() != 1 ||
        header.getInt(24) != 16_000 || header.getInt(28) != 32_000 ||
        header.getShort(32).toInt() != 2 || header.getShort(34).toInt() != 16 ||
        String(wav, 36, 4, Charsets.US_ASCII) != "data" ||
        header.getInt(40) != wav.size - 44 || (wav.size - 44) % 2 != 0) {
        throw SpeechFailure(SpeechErrorCode.AUDIO, "百度识别需要 16 kHz 单声道 PCM WAV")
    }
    return wav.copyOfRange(44, wav.size)
}

internal suspend fun recognizeBaidu(config: BaiduSpeechConfig, key: String, secret: String, wav: ByteArray): String {
    // Validate audio and config before exchanging credentials or sending any audio.
    validateBaiduSpeechConfig(config, synthesis = false)
    baiduRecognitionPcm(wav)
    val scope = if (config.devPid == 80001) "brain_enhanced_asr" else "audio_voice_assistant_get"
    val token = baiduAccessToken(SpeechHttp.json(baiduTokenRequest(key, secret)), scope)
    return baiduTranscript(SpeechHttp.json(baiduRecognitionRequest(config, token, wav)))
}

internal fun baiduTranscript(response: JSONObject): String {
    if (!response.has("err_no")) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "百度未返回识别状态")
    val code = response.optInt("err_no", -1)
    if (code != 0) throw baiduServiceFailure(code, synthesis = false)
    val results = response.optJSONArray("result")
        ?: throw SpeechFailure(SpeechErrorCode.PROTOCOL, "百度识别结果格式无效")
    if (results.length() == 0) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到清晰语音，请重试")
    val text = (results.opt(0) as? String)?.trim()
        ?: throw SpeechFailure(SpeechErrorCode.PROTOCOL, "百度识别结果格式无效")
    return text.ifEmpty { throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到清晰语音，请重试") }
}

internal fun baiduSynthesisRequest(config: BaiduSpeechConfig, token: String, text: String): Request {
    validateBaiduSpeechConfig(config, synthesis = true)
    if (text.isBlank() || text.toByteArray(Charsets.UTF_8).size > 120) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "百度单段播报文本为空或过长")
    }
    // Official REST demo requires two URL encodings: pre-encode tex, then encode the form.
    val form = FormBody.Builder().add("tok", token)
        .add("tex", URLEncoder.encode(text, "UTF-8"))
        .add("cuid", UUID.randomUUID().toString()).add("ctp", "1").add("lan", "zh")
        .add("per", config.voice.toString()).add("spd", config.speed.toString())
        .add("pit", config.pitch.toString()).add("vol", config.volume.toString()).add("aue", "4").build()
    return Request.Builder().url("https://tsn.baidu.com/text2audio").post(form).build()
}

/** Conservative UTF-8 byte cap also fits the product's 120 GBK-byte short-text allowance.
 * Unicode code points remain intact; neither text nor whitespace is discarded.
 */
internal fun baiduSpeechChunks(text: String): List<String> {
    if (text.isBlank()) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "播报文本不能为空")
    val chunks = mutableListOf<String>()
    var start = 0
    var offset = 0
    var bytes = 0
    while (offset < text.length) {
        val next = offset + Character.charCount(text.codePointAt(offset))
        val count = text.substring(offset, next).toByteArray(Charsets.UTF_8).size
        if (bytes + count > 120) {
            chunks += text.substring(start, offset)
            start = offset
            bytes = 0
        }
        bytes += count
        offset = next
    }
    chunks += text.substring(start)
    return chunks
}

internal suspend fun synthesizeBaidu(
    config: BaiduSpeechConfig, key: String, secret: String, text: String, onAudio: (ByteArray) -> Unit,
) {
    validateBaiduSpeechConfig(config, synthesis = true)
    val chunks = baiduSpeechChunks(text)
    val token = baiduAccessToken(SpeechHttp.json(baiduTokenRequest(key, secret)), "audio_tts_post")
    for (chunk in chunks) {
        // A whitespace-only segment needs no network call; surrounding text is still preserved.
        if (chunk.isBlank()) continue
        SpeechHttp.read(baiduSynthesisRequest(config, token, chunk)) { response ->
            val mediaType = response.body.contentType()
            if (mediaType?.type != "audio") {
                val source = response.body.source()
                if (source.request(SpeechHttp.MAX_JSON_BYTES + 1)) {
                    throw SpeechFailure(SpeechErrorCode.PROTOCOL, "百度错误响应过大")
                }
                val error = try { JSONObject(source.readUtf8()) } catch (_: org.json.JSONException) {
                    throw SpeechFailure(SpeechErrorCode.PROTOCOL, "百度未返回 PCM 音频")
                }
                if (!error.has("err_no")) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "百度未返回合成状态")
                throw baiduServiceFailure(error.optInt("err_no", -1), synthesis = true)
            }
            if (mediaType?.subtype != "pcm") {
                throw SpeechFailure(SpeechErrorCode.PROTOCOL, "百度未返回所请求的 16 kHz PCM 音频")
            }
            val decoder = BaiduPcm16Decoder(onAudio)
            val source = response.body.source()
            var total = 0L
            while (!source.exhausted()) {
                val bytes = source.readByteArray(minOf(8192L, source.buffer.size))
                total += bytes.size
                if (total > 16L * 1024 * 1024) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "百度音频响应过大")
                decoder.write(bytes)
            }
            decoder.end()
        }
    }
}

/** aue=4 is raw 16 kHz PCM16 mono; preserve a split sample across transport chunks. */
internal class BaiduPcm16Decoder(onAudio: (ByteArray) -> Unit) {
    private val resampler = StreamingPcmResampler(16_000, 24_000)
    private val audio = SpeechSegmentAudio(onAudio)
    private var pending: Byte? = null
    private var samples = 0L

    fun write(bytes: ByteArray) {
        val joined = pending?.let { byteArrayOf(it) + bytes } ?: bytes
        val count = joined.size / 2
        pending = if (joined.size % 2 == 1) joined.last() else null
        if (count == 0) return
        val floats = FloatArray(count) { index ->
            val value = (joined[index * 2].toInt() and 255) or (joined[index * 2 + 1].toInt() shl 8)
            value.toShort() / 32768f
        }
        samples += count
        audio.write(floatPcmToPcm16(resampler.process(floats)))
    }

    fun end() {
        if (pending != null || samples == 0L) {
            throw SpeechFailure(SpeechErrorCode.PROTOCOL, "百度 PCM 音频为空或采样不完整")
        }
        audio.end(completed = true)
    }
}

private fun baiduServiceFailure(code: Int, synthesis: Boolean): SpeechFailure = when {
    (!synthesis && code == 3301) -> SpeechFailure(SpeechErrorCode.NO_SPEECH, "百度未识别到清晰语音，请重试")
    code in setOf(110, 111, 502, 3302) -> SpeechFailure(SpeechErrorCode.AUTHENTICATION, "百度语音认证失败，请检查凭据和服务权限")
    code in setOf(4, 17, 18, 19, 3304, 3305) -> SpeechFailure(SpeechErrorCode.RATE_LIMITED, "百度语音额度不足或请求过于频繁，请稍后重试")
    else -> SpeechFailure(SpeechErrorCode.SERVER, "百度语音服务失败（$code），请检查配置和服务权限")
}
