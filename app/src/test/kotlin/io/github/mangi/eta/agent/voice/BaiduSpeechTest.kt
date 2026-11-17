package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.BaiduSpeechConfig
import java.net.URLDecoder
import java.util.Base64
import okhttp3.FormBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BaiduSpeechTest {
    @Test fun oauthCredentialsStayInPostBodyAndScopeIsRequired() {
        val request = baiduTokenRequest(" api-key ", " secret-key ")
        assertEquals("https://aip.baidubce.com/oauth/2.0/token", request.url.toString())
        assertNull(request.url.query)
        val form = request.body as FormBody
        assertEquals("api-key", form.value(1))
        assertEquals("secret-key", form.value(2))
        assertEquals("token", baiduAccessToken(JSONObject()
            .put("access_token", "token").put("scope", "audio_tts_post other"), "audio_tts_post"))
        assertEquals(SpeechErrorCode.AUTHENTICATION, failure {
            baiduAccessToken(JSONObject().put("access_token", "sensitive-token")
                .put("scope", "other"), "audio_tts_post")
        }.code)
        val error = failure { baiduAccessToken(JSONObject().put("error", "sensitive-secret"), "audio_tts_post") }
        assertFalse(error.message.orEmpty().contains("sensitive-secret"))
        assertEquals(SpeechErrorCode.CONFIGURATION, failure { baiduTokenRequest("a\nb", "key") }.code)
    }

    @Test fun recognitionSendsPcmWithByteLengthAndModelSpecificEndpoint() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val request = baiduRecognitionRequest(BaiduSpeechConfig(), "token", pcmToWav(pcm))
        assertEquals("https://vop.baidu.com/server_api", request.url.toString())
        assertNull(request.url.query)
        val body = Buffer(); request.body!!.writeTo(body)
        val json = JSONObject(body.readUtf8())
        assertEquals("pcm", json.getString("format"))
        assertEquals(16000, json.getInt("rate"))
        assertEquals(1, json.getInt("channel"))
        assertEquals(4, json.getInt("len"))
        assertEquals(1537, json.getInt("dev_pid"))
        assertArrayEquals(pcm, Base64.getDecoder().decode(json.getString("speech")))
        assertEquals("/pro_api", baiduRecognitionRequest(BaiduSpeechConfig(devPid = 80001), "token", pcmToWav(pcm)).url.encodedPath)
    }

    @Test fun malformedOversizedAndWrongRateAudioAreRejectedBeforeSending() {
        listOf(ByteArray(44), pcmToWav(ByteArray(32000 * 60 + 2)),
            pcmToWav(ByteArray(100), 24000), pcmToWav(ByteArray(3)),
            pcmToWav(ByteArray(100)).also { it[4] = 0 }).forEach { wav ->
            assertEquals(SpeechErrorCode.AUDIO, failure {
                baiduRecognitionRequest(BaiduSpeechConfig(), "token", wav)
            }.code)
        }
        assertEquals(SpeechErrorCode.CONFIGURATION, failure {
            baiduRecognitionRequest(BaiduSpeechConfig(devPid = 8001), "token", pcmToWav(ByteArray(10)))
        }.code)
    }

    @Test fun providerErrorsAndInvalidResultsNeverBecomeTranscripts() {
        assertEquals("你好", baiduTranscript(JSONObject("{\"err_no\":0,\"result\":[\" 你好 \"]}")))
        assertEquals(SpeechErrorCode.NO_SPEECH, failure { baiduTranscript(JSONObject().put("err_no", 3301)) }.code)
        assertEquals(SpeechErrorCode.AUTHENTICATION, failure { baiduTranscript(JSONObject().put("err_no", 3302)) }.code)
        assertEquals(SpeechErrorCode.PROTOCOL, failure { baiduTranscript(JSONObject()) }.code)
        assertEquals(SpeechErrorCode.PROTOCOL, failure { baiduTranscript(JSONObject("{\"err_no\":0,\"result\":[123]}")) }.code)
        val error = failure { baiduTranscript(JSONObject().put("err_no", 9999).put("err_msg", "secret-token")) }
        assertFalse(error.message.orEmpty().contains("secret-token"))
    }

    @Test fun synthesisTextIsDoubleEncodedAndAudioIs16kPcm() {
        val text = "中文 &+=% 😀"
        val request = baiduSynthesisRequest(BaiduSpeechConfig(), "token", text)
        assertEquals("https://tsn.baidu.com/text2audio", request.url.toString())
        assertNull(request.url.query)
        val form = request.body as FormBody
        val values = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals(text, URLDecoder.decode(values.getValue("tex"), "UTF-8"))
        assertEquals("4", values["aue"])
        assertEquals("zh", values["lan"])
        assertEquals("1", values["ctp"])
        assertEquals("0", values["per"])
        assertEquals(SpeechErrorCode.CONFIGURATION, failure {
            baiduSynthesisRequest(BaiduSpeechConfig(volume = 10), "token", text)
        }.code)
    }

    @Test fun longTextChunksPreserveUnicodeAndStayWithinByteCap() {
        val text = ("😀汉字 +&\n".repeat(100))
        val chunks = baiduSpeechChunks(text)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 120 })
        assertTrue(chunks.all { !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }

    @Test fun pcmTransportBoundariesPreserveSamplesAndConvertToPlaybackRate() {
        val pcm = ByteArray(32000) { (it % 100).toByte() }
        val out = Buffer()
        val decoder = BaiduPcm16Decoder { out.write(it) }
        decoder.write(pcm.copyOfRange(0, 3))
        decoder.write(pcm.copyOfRange(3, pcm.size))
        decoder.end()
        // The shared linear resampler may round its final fractional position by one sample.
        assertTrue(out.size in 48000L..48002L)
        val odd = BaiduPcm16Decoder {}
        odd.write(byteArrayOf(1, 2, 3))
        assertEquals(SpeechErrorCode.PROTOCOL, failure { odd.end() }.code)
        assertEquals(SpeechErrorCode.PROTOCOL, failure { BaiduPcm16Decoder {}.end() }.code)
    }

    private fun failure(action: () -> Unit): SpeechFailure = try {
        action(); throw AssertionError("Expected SpeechFailure")
    } catch (error: SpeechFailure) { error }
}
