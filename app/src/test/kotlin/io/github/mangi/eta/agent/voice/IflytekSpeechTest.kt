package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.IflytekSpeechConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.util.Base64
import java.util.Date
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class IflytekSpeechTest {
    private val config = IflytekSpeechConfig(appId = "12345678")

    @Test fun signingMatchesOfficialPublishedAsrFixture() {
        val secret = "secretxxxxxxxx2df7900c09xxxxxxxx"
        val request = iflytekSpeechRequest("keyxxxxxxxx8ee279348519exxxxxxxx", secret, false,
            now = Date.from(Instant.parse("2019-07-10T07:35:43Z")))
        assertEquals("iat-api.xfyun.cn", request.url.host)
        assertEquals("/v2/iat", request.url.encodedPath)
        assertEquals("Wed, 10 Jul 2019 07:35:43 GMT", request.url.queryParameter("date"))
        val authorization = String(Base64.getDecoder().decode(request.url.queryParameter("authorization")), Charsets.UTF_8)
        assertTrue(authorization.contains("signature=\"Hp3Ty4ZkSBmL8jKyOLpQiv9Sr5nvmeYEH7WsL/ZO2Jg=\""))
        assertTrue(authorization.contains("headers=\"host date request-line\""))
        assertFalse(request.url.toString().contains(secret))
    }

    @Test fun synthesisAndNicheLanguagesUseTheirOwnOfficialSignedHosts() {
        assertEquals("tts-api.xfyun.cn", iflytekSpeechRequest("key", "secret", true).url.host)
        assertEquals("/v2/tts", iflytekSpeechRequest("key", "secret", true).url.encodedPath)
        assertEquals("iat-niche-api.xfyun.cn", iflytekSpeechRequest("key", "secret", false, "ja_jp").url.host)
        assertEquals(SpeechErrorCode.CONFIGURATION, failure { validateIflytekSpeechKeys("key\nvalue", "secret") }.code)
        assertEquals(SpeechErrorCode.CONFIGURATION, failure { validateIflytekSpeechConfig(config.copy(appId = "id\""), false) }.code)
    }

    @Test fun audioSequenceCarriesConfigurationOnlyInFirstFrameAndAlwaysEnds() {
        val first = iflytekAsrFrame(config, 0, ByteArray(1280), 2000)
        assertEquals("wpgs", first.getJSONObject("business").getString("dwa"))
        assertEquals(2000, first.getJSONObject("business").getInt("eos"))
        assertEquals("12345678", first.getJSONObject("common").getString("app_id"))
        assertArrayEquals(ByteArray(1280), Base64.getDecoder().decode(first.getJSONObject("data").getString("audio")))
        assertFalse(iflytekAsrFrame(config, 1, ByteArray(1280), 2000).has("business"))
        val last = iflytekAsrFrame(config, 2, byteArrayOf(), 2000)
        assertEquals(2, last.getJSONObject("data").getInt("status"))
        assertEquals("", last.getJSONObject("data").getString("audio"))
        assertEquals(SpeechErrorCode.AUDIO, failure { iflytekAsrFrame(config, 1, ByteArray(3), 2000) }.code)
        assertEquals(SpeechErrorCode.AUDIO, failure { iflytekAsrFrame(config, 1, ByteArray(1282), 2000) }.code)
    }

    @Test fun correctionsReplaceInclusiveSequencesAndKeepUnaffectedText() {
        val transcript = IflytekTranscript()
        assertEquals("你好", transcript.accept(result(0, "你好")))
        assertEquals("你好错词", transcript.accept(result(1, "错词")))
        assertEquals("你好错词再错", transcript.accept(result(2, "再错")))
        val replacement = result(3, "世界").put("pgs", "rpl").put("rg", org.json.JSONArray("[1,2]"))
        assertEquals("你好世界", transcript.accept(replacement))
        assertEquals("你好世界。", transcript.accept(result(4, "。")))
        assertEquals("你好世界。", transcript.accept(result(4, "。")))
        assertEquals(SpeechErrorCode.PROTOCOL, failure {
            transcript.accept(result(5, "x").put("pgs", "rpl").put("rg", org.json.JSONArray("[3,1]")))
        }.code)
    }

    @Test fun failuresAndMissingCodeNeverProduceSuccess() {
        listOf(10005, 11200).forEach { assertEquals(SpeechErrorCode.AUTHENTICATION, serviceFailure(it).code) }
        listOf(11201, 11202, 11203).forEach { assertEquals(SpeechErrorCode.RATE_LIMITED, serviceFailure(it).code) }
        assertEquals(SpeechErrorCode.TIMEOUT, serviceFailure(10114).code)
        assertEquals(SpeechErrorCode.SERVER, serviceFailure(99999).code)
        assertFalse(serviceFailure(10005).message!!.contains("secret"))
        assertEquals(SpeechErrorCode.PROTOCOL, failure { checkIflytekResponse(JSONObject()) }.code)
    }

    @Test fun ttsSendsWholeUtf8TextAndRequestsRaw16kForConversion() {
        val payload = iflytekTtsPayload(config, "你好 & <世界>")
        assertEquals("raw", payload.getJSONObject("business").getString("aue"))
        assertEquals("audio/L16;rate=16000", payload.getJSONObject("business").getString("auf"))
        assertEquals("UTF8", payload.getJSONObject("business").getString("tte"))
        assertEquals(2, payload.getJSONObject("data").getInt("status"))
        assertEquals("你好 & <世界>", String(Base64.getDecoder().decode(payload.getJSONObject("data").getString("text")), Charsets.UTF_8))
        assertEquals(SpeechErrorCode.CONFIGURATION, failure { iflytekTtsPayload(config, "a".repeat(8000)) }.code)
        assertEquals(SpeechErrorCode.CONFIGURATION, failure { iflytekTtsPayload(config.copy(speed = 101), "a") }.code)
    }

    @Test fun wavMustBeCanonicalMono16kAndAtMost60Seconds() {
        assertArrayEquals(ByteArray(100), iflytekWavPcm(pcmToWav(ByteArray(100))))
        assertEquals(SpeechErrorCode.AUDIO, failure { iflytekWavPcm(pcmToWav(ByteArray(100), 24_000)) }.code)
        assertEquals(SpeechErrorCode.AUDIO, failure { iflytekWavPcm(pcmToWav(ByteArray(32_000 * 60 + 2))) }.code)
        assertEquals(SpeechErrorCode.AUDIO, failure { iflytekWavPcm(byteArrayOf()) }.code)
    }

    @Test fun resamplingPreservesSampleCountAndInterpolationAcrossEveryByteBoundary() {
        val pcm = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN)
        repeat(100) { pcm.putShort((it * 300 - 15000).toShort()) }
        fun convert(split: Boolean): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val resampler = IflytekPcm16To24(output::write)
            if (split) pcm.array().forEach { resampler.write(byteArrayOf(it)) } else resampler.write(pcm.array())
            resampler.end()
            return output.toByteArray()
        }
        val full = convert(false)
        assertEquals(300, full.size)
        assertArrayEquals(full, convert(true))
        val samples = ByteBuffer.wrap(full).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(-15000, samples.short.toInt())
        assertEquals(-14800, samples.short.toInt())
        assertEquals(-14600, samples.short.toInt())
        val bad = IflytekPcm16To24 {}
        bad.write(byteArrayOf(1))
        assertEquals(SpeechErrorCode.PROTOCOL, failure { bad.end() }.code)
    }

    private fun result(sn: Int, word: String): JSONObject = JSONObject().put("sn", sn).put("pgs", "apd")
        .put("ws", org.json.JSONArray().put(JSONObject().put("cw", org.json.JSONArray().put(JSONObject().put("w", word)))))
    private fun serviceFailure(code: Int): SpeechFailure = failure {
        checkIflytekResponse(JSONObject().put("code", code).put("message", "secret"))
    }
    private fun failure(action: () -> Unit): SpeechFailure = try {
        action(); throw AssertionError("Expected SpeechFailure")
    } catch (error: SpeechFailure) { error }
}
