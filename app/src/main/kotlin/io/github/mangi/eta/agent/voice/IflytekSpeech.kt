package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.IflytekSpeechConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

// Official protocol references (verified 2026-11-15):
// https://www.xfyun.cn/doc/asr/voicedictation/API.html
// https://www.xfyun.cn/doc/tts/online_tts/API.html
internal fun validateIflytekSpeechConfig(config: IflytekSpeechConfig, synthesis: Boolean) {
    if (!Regex("[A-Za-z0-9_-]{1,64}").matches(config.appId))
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请填写有效的讯飞 App ID")
    if (synthesis) {
        if (!Regex("[A-Za-z0-9_-]{1,128}").matches(config.voice) || config.speed !in 0..100)
            throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请检查讯飞音色和语速（0～100）")
    } else if (!Regex("[A-Za-z0-9_-]{1,64}").matches(config.language) ||
        !Regex("[A-Za-z0-9_-]{1,64}").matches(config.accent)) {
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请填写讯飞控制台提供的语种和方言参数")
    }
}

internal fun validateIflytekSpeechKeys(key: String, secret: String) {
    if (!Regex("[A-Za-z0-9_-]{1,256}").matches(key.trim()) ||
        !Regex("[A-Za-z0-9_-]{1,256}").matches(secret.trim()))
        throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "请填写有效的讯飞 API Key 和 API Secret")
}

/** Signs only fixed official hosts; API Secret is never included in the request. */
internal fun iflytekSpeechRequest(key: String, secret: String, synthesis: Boolean,
    language: String = "zh_cn", now: Date = Date()): Request {
    validateIflytekSpeechKeys(key, secret)
    val host = if (synthesis) "tts-api.xfyun.cn" else if (language in listOf("zh_cn", "en_us"))
        "iat-api.xfyun.cn" else "iat-niche-api.xfyun.cn"
    val path = if (synthesis) "/v2/tts" else "/v2/iat"
    val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("GMT")
    }.format(now)
    val canonical = "host: $host\ndate: $date\nGET $path HTTP/1.1"
    val mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(secret.trim().toByteArray(Charsets.UTF_8), "HmacSHA256"))
    }
    val signature = Base64.getEncoder().encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
    val authorization = "api_key=\"${key.trim()}\",algorithm=\"hmac-sha256\",headers=\"host date request-line\",signature=\"$signature\""
    val url = "https://$host$path".toHttpUrl().newBuilder()
        .addQueryParameter("authorization", Base64.getEncoder().encodeToString(authorization.toByteArray(Charsets.UTF_8)))
        .addQueryParameter("date", date).addQueryParameter("host", host).build()
    return Request.Builder().url(url.toString().replaceFirst("https://", "wss://")).build()
}

internal fun checkIflytekResponse(message: JSONObject) {
    if (!message.has("code")) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "讯飞响应缺少状态码")
    val code = message.getInt("code")
    if (code == 0) return
    val type = when (code) {
        10005, 11200, 11204 -> SpeechErrorCode.AUTHENTICATION
        10010, 10110, 11201, 11202, 11203 -> SpeechErrorCode.RATE_LIMITED
        10014, 10019, 10114, 10200, 10700 -> SpeechErrorCode.TIMEOUT
        10043, 10044 -> SpeechErrorCode.AUDIO
        10006, 10007, 10139, 10163, 10313, 10404 -> SpeechErrorCode.CONFIGURATION
        else -> SpeechErrorCode.SERVER
    }
    // Do not echo arbitrary server messages, which may contain submitted credentials or text.
    throw SpeechFailure(type, "讯飞语音服务失败（$code），请检查凭据、套餐和参数")
}

internal fun iflytekAsrFrame(config: IflytekSpeechConfig, status: Int, audio: ByteArray,
    endpointSilenceMs: Int): JSONObject {
    if (status !in 0..2 || audio.size > 1280 || audio.size % 2 != 0)
        throw SpeechFailure(SpeechErrorCode.AUDIO, "讯飞识别需要完整的 16 kHz PCM 音频帧")
    val message = JSONObject().put("data", JSONObject().put("status", status)
        .put("format", "audio/L16;rate=16000").put("encoding", "raw")
        .put("audio", Base64.getEncoder().encodeToString(audio)))
    if (status == 0) {
        val business = JSONObject().put("language", config.language).put("domain", "iat")
            .put("accent", config.accent).put("eos", if (endpointSilenceMs > 0) endpointSilenceMs else 10_000)
        if (config.language == "zh_cn" && config.accent == "mandarin") business.put("dwa", "wpgs")
        message.put("common", JSONObject().put("app_id", config.appId)).put("business", business)
    }
    return message
}

/** Dynamic correction replaces inclusive response sequence ranges rather than appending duplicates. */
internal class IflytekTranscript {
    private val parts = sortedMapOf<Int, String>()
    fun accept(result: JSONObject): String {
        val sequence = result.getInt("sn")
        if (sequence < 0 || sequence > 10_000) invalid()
        when (result.optString("pgs")) {
            "", "apd" -> Unit
            "rpl" -> {
                val range = result.getJSONArray("rg")
                if (range.length() != 2) invalid()
                val start = range.getInt(0); val end = range.getInt(1)
                if (start < 0 || end < start || end > 10_000) invalid()
                parts.keys.filter { it in start..end }.forEach(parts::remove)
            }
            else -> invalid()
        }
        val words = result.getJSONArray("ws")
        val text = buildString {
            for (index in 0 until words.length()) {
                val candidates = words.getJSONObject(index).getJSONArray("cw")
                if (candidates.length() > 0) append(candidates.getJSONObject(0).getString("w"))
            }
        }
        parts[sequence] = text
        if (parts.values.sumOf { it.length } > 100_000) invalid()
        return result()
    }
    fun result(): String = parts.values.joinToString("").trim()
    private fun invalid(): Nothing = throw SpeechFailure(SpeechErrorCode.PROTOCOL, "讯飞识别结果格式无效")
}

internal suspend fun recognizeIflytekStream(config: IflytekSpeechConfig, key: String, secret: String,
    frames: ReceiveChannel<ByteArray>, onReady: () -> Unit, onPreview: (String) -> Unit,
    onEndpoint: () -> Unit, endpointSilenceMs: Int): String = withTimeout(90_000) {
    validateIflytekSpeechConfig(config, false)
    coroutineScope {
        SpeechSocket(iflytekSpeechRequest(key, secret, false, config.language)).use { socket ->
            socket.awaitReady()
            onReady()
            val sender = launch {
                var first = true
                var total = 0L
                for (frame in frames) {
                    if (frame.size % 2 != 0) throw SpeechFailure(SpeechErrorCode.AUDIO, "讯飞识别音频帧不完整")
                    total += frame.size
                    if (total > 16_000 * 2 * 60) throw SpeechFailure(SpeechErrorCode.AUDIO, "讯飞听写最多支持 60 秒音频")
                    var offset = 0
                    while (offset < frame.size) {
                        val count = minOf(1280, frame.size - offset)
                        socket.text(iflytekAsrFrame(config, if (first) 0 else 1,
                            frame.copyOfRange(offset, offset + count), endpointSilenceMs).toString())
                        first = false
                        offset += count
                        delay((count * 1000L / 32_000).coerceAtLeast(1))
                    }
                }
                if (first) throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有采集到语音，请重试")
                socket.text(iflytekAsrFrame(config, 2, byteArrayOf(), endpointSilenceMs).toString())
            }
            val transcript = IflytekTranscript()
            try {
                for (bytes in socket.messages) {
                    val response = JSONObject(bytes.toString(Charsets.UTF_8))
                    checkIflytekResponse(response)
                    val data = response.getJSONObject("data")
                    data.optJSONObject("result")?.let { onPreview(transcript.accept(it)) }
                    val status = data.getInt("status")
                    if (status !in 0..2) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "讯飞返回未知音频状态")
                    if (status == 2) {
                        val text = transcript.result().ifEmpty {
                            throw SpeechFailure(SpeechErrorCode.NO_SPEECH, "没有识别到清晰语音，请重试")
                        }
                        if (endpointSilenceMs > 0) onEndpoint()
                        return@coroutineScope text
                    }
                }
                throw SpeechFailure(SpeechErrorCode.PROTOCOL, "讯飞识别连接提前结束")
            } finally { sender.cancel() }
        }
    }
}

internal suspend fun recognizeIflytek(config: IflytekSpeechConfig, key: String, secret: String, wav: ByteArray): String {
    val pcm = iflytekWavPcm(wav)
    return coroutineScope {
        val frames = Channel<ByteArray>(1)
        val producer = launch { try {
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + 1280, pcm.size)
                frames.send(pcm.copyOfRange(offset, end)); offset = end
            }
        } finally { frames.close() } }
        try { recognizeIflytekStream(config, key, secret, frames, {}, {}, {}, 0) }
        finally { producer.cancel(); frames.cancel() }
    }
}

internal fun iflytekWavPcm(wav: ByteArray): ByteArray {
    if (wav.size <= 44 || wav.size > 44 + 32_000 * 60) invalidIflytekWav()
    val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    if (String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" ||
        String(wav, 8, 8, Charsets.US_ASCII) != "WAVEfmt " || header.getInt(16) != 16 ||
        header.getShort(20).toInt() != 1 || header.getShort(22).toInt() != 1 ||
        header.getInt(24) != 16_000 || header.getInt(28) != 32_000 ||
        header.getShort(32).toInt() != 2 || header.getShort(34).toInt() != 16 ||
        String(wav, 36, 4, Charsets.US_ASCII) != "data" || header.getInt(40) != wav.size - 44 ||
        (wav.size - 44) % 2 != 0) invalidIflytekWav()
    return wav.copyOfRange(44, wav.size)
}
private fun invalidIflytekWav(): Nothing = throw SpeechFailure(SpeechErrorCode.AUDIO, "讯飞听写需要 60 秒以内的 16 kHz 单声道 PCM WAV")

internal fun iflytekTtsPayload(config: IflytekSpeechConfig, text: String): JSONObject {
    validateIflytekSpeechConfig(config, true)
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (text.isBlank() || bytes.size >= 8000) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "讯飞单段朗读文本须少于 8000 字节")
    return JSONObject().put("common", JSONObject().put("app_id", config.appId))
        .put("business", JSONObject().put("aue", "raw").put("auf", "audio/L16;rate=16000")
            .put("vcn", config.voice).put("speed", config.speed).put("tte", "UTF8"))
        .put("data", JSONObject().put("status", 2).put("text", Base64.getEncoder().encodeToString(bytes)))
}

internal suspend fun synthesizeIflytek(config: IflytekSpeechConfig, key: String, secret: String,
    text: String, onAudio: (ByteArray) -> Unit) = withTimeout(120_000) {
    val payload = iflytekTtsPayload(config, text)
    SpeechSocket(iflytekSpeechRequest(key, secret, true)).use { socket ->
        socket.awaitReady(); socket.text(payload.toString())
        val audio = SpeechSegmentAudio(onAudio)
        val resampler = IflytekPcm16To24(audio::write)
        for (bytes in socket.messages) {
            val response = JSONObject(bytes.toString(Charsets.UTF_8))
            checkIflytekResponse(response)
            val data = response.getJSONObject("data")
            val status = data.getInt("status")
            if (status !in 1..2) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "讯飞返回未知合成状态")
            val encoded = data.optString("audio")
            if (encoded.isNotEmpty()) resampler.write(Base64.getDecoder().decode(encoded))
            if (status == 2) {
                resampler.end(); audio.end(completed = true)
                return@withTimeout
            }
        }
        throw SpeechFailure(SpeechErrorCode.PROTOCOL, "讯飞合成连接提前结束")
    }
}

/** Integer phase preserves interpolation across PCM chunks, including split sample bytes. */
internal class IflytekPcm16To24(private val onAudio: (ByteArray) -> Unit) {
    private var total = 0L
    private var position = 0L // Source position in thirds, incremented by two per 24 kHz sample.
    private var last: Int? = null
    private var oddByte: Byte? = null
    fun write(bytes: ByteArray) {
        val joined = oddByte?.let { byteArrayOf(it) + bytes } ?: bytes
        oddByte = if (joined.size % 2 == 1) joined.last() else null
        val count = joined.size / 2
        if (count == 0) return
        if (total + count > 16_000 * 60 * 10) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "讯飞合成音频过长")
        val start = if (last == null) total else total - 1
        val samples = IntArray(count + if (last == null) 0 else 1)
        val prefix = if (last == null) 0 else 1
        last?.let { samples[0] = it }
        for (index in 0 until count) samples[index + prefix] =
            (((joined[index * 2].toInt() and 255) or (joined[index * 2 + 1].toInt() shl 8)).toShort()).toInt()
        total += count
        val output = ByteBuffer.allocate((count * 2 + 3) * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (position / 3 + 1 < total) {
            val index = (position / 3 - start).toInt()
            val value = samples[index] + (samples[index + 1] - samples[index]) * (position % 3).toInt() / 3
            output.putShort(value.toShort()); position += 2
        }
        last = samples.last()
        if (output.position() > 0) onAudio(output.array().copyOf(output.position()))
    }
    fun end() {
        if (oddByte != null || total == 0L) throw SpeechFailure(SpeechErrorCode.PROTOCOL, "讯飞合成 PCM 音频不完整")
        val output = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        while (position / 3 < total) { output.putShort(last!!.toShort()); position += 2 }
        if (output.position() > 0) onAudio(output.array().copyOf(output.position()))
    }
}
