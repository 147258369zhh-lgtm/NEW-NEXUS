package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.XiaomiMiMoSpeechConfig
import java.util.Base64
import okhttp3.Request
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class XiaomiMiMoSpeechTest {
    @Test fun recognitionUsesOfficialModelWavAndLanguageWithoutCredentialsInUrl() {
        val wav = pcmToWav(byteArrayOf(1, 2, 3, 4))
        val request = xiaomiMiMoRecognitionRequest(XiaomiMiMoSpeechConfig(language = "zh"), " sk-test-key ", wav)
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", request.url.toString())
        assertEquals("Bearer sk-test-key", request.header("Authorization"))
        assertNull(request.url.query)
        val body = body(request)
        assertEquals("mimo-v2.5-asr", body.getString("model"))
        assertFalse(body.getBoolean("stream"))
        assertEquals("zh", body.getJSONObject("asr_options").getString("language"))
        val message = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        val input = message.getJSONArray("content").getJSONObject(0)
        assertEquals("input_audio", input.getString("type"))
        assertEquals("wav", input.getJSONObject("input_audio").getString("format"))
        assertArrayEquals(wav, Base64.getDecoder().decode(input.getJSONObject("input_audio").getString("data")))
    }

    @Test fun unsupportedKeysLanguageAndAudioFailBeforeSending() {
        for (key in listOf("", "tp-subscription", "ttp-team", "sk-line\nbreak", "https://example.invalid")) {
            assertEquals(SpeechErrorCode.CONFIGURATION, failure { validateXiaomiMiMoKey(key) }.code)
        }
        assertEquals(SpeechErrorCode.CONFIGURATION, failure {
            validateXiaomiMiMoConfig(XiaomiMiMoSpeechConfig(language = "zh-CN"), synthesis = false)
        }.code)
        for (wav in listOf(ByteArray(44), pcmToWav(ByteArray(3)),
            pcmToWav(ByteArray(7_500_000)), pcmToWav(ByteArray(10)).also { it[34] = 8 })) {
            assertEquals(SpeechErrorCode.AUDIO, failure {
                xiaomiMiMoRecognitionRequest(XiaomiMiMoSpeechConfig(), "sk-test", wav)
            }.code)
        }
    }

    @Test fun transcriptsRequireSuccessfulCompletionAndStringContent() {
        assertEquals("你好", xiaomiMiMoTranscript(JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":" 你好 "}}]}""")))
        assertEquals(SpeechErrorCode.NO_SPEECH, failure {
            xiaomiMiMoTranscript(JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":" "}}]}"""))
        }.code)
        for (reason in listOf("length", "content_filter", "unknown")) {
            failure { xiaomiMiMoTranscript(JSONObject("""{"choices":[{"finish_reason":"$reason","message":{"content":"partial"}}]}""")) }
        }
        assertEquals(SpeechErrorCode.PROTOCOL, failure {
            xiaomiMiMoTranscript(JSONObject("""{"choices":[{"finish_reason":null,"message":{"content":"partial"}}]}"""))
        }.code)
        assertEquals(SpeechErrorCode.PROTOCOL, failure {
            xiaomiMiMoTranscript(JSONObject("""{"choices":[{"finish_reason":"stop","message":{"content":123}}]}"""))
        }.code)
    }

    @Test fun synthesisRequestsDocumented24kPcmAndUsesAssistantText() {
        val text = "中文 & <tag> 😀"
        val request = xiaomiMiMoSynthesisRequest(XiaomiMiMoSpeechConfig(voice = "茉莉"), "sk-test", text)
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", request.url.toString())
        assertEquals("text/event-stream", request.header("Accept"))
        val body = body(request)
        assertEquals("mimo-v2.5-tts", body.getString("model"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("pcm16", body.getJSONObject("audio").getString("format"))
        assertEquals("茉莉", body.getJSONObject("audio").getString("voice"))
        assertFalse(body.getJSONObject("audio").has("sample_rate"))
        val message = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("assistant", message.getString("role"))
        assertEquals(text, message.getString("content"))
        assertEquals(SpeechErrorCode.CONFIGURATION, failure {
            xiaomiMiMoSynthesisRequest(XiaomiMiMoSpeechConfig(voice = "custom"), "sk-test", text)
        }.code)
    }

    @Test fun longTextPreservesCodePointsAndFitsLocalResponsePolicy() {
        val text = "😀中文  &\n".repeat(100)
        val chunks = xiaomiMiMoSpeechChunks(text)
        assertTrue(chunks.size > 1)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 512 && it.codePointCount(0, it.length) <= 240 })
        assertTrue(chunks.all { !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        assertEquals(listOf("a".repeat(240), "a"), xiaomiMiMoSpeechChunks("a".repeat(241)))
    }

    @Test fun fragmentedSsePreservesSplitSamplesAndStopsAtSuccess() {
        val out = Buffer()
        val decoder = XiaomiMiMoSpeechDecoder { out.write(it) }
        val wire = Buffer().writeUtf8(
            ": keepalive\r\n\r\n" +
                "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"AQ==\"}},\"finish_reason\":null}]}\r\n\r\n" +
                "data: {\"choices\":[{\"delta\":{\"audio\":{\"data\":\"AgME\"}},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: invalid trailing payload\n\n",
        )
        val fragmented = object : ForwardingSource(wire) {
            override fun read(sink: Buffer, byteCount: Long) = super.read(sink, minOf(byteCount, 1))
        }.buffer()
        readSpeechSse(fragmented) { _, data -> decoder.event(data) }
        decoder.end()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), out.readByteArray())
    }

    @Test fun doneEofAndTruncatedSamplesAreNotSuccess() {
        for (terminal in listOf("", "[DONE]")) {
            val decoder = XiaomiMiMoSpeechDecoder {}
            decoder.event("""{"choices":[{"delta":{"audio":{"data":"AQI="}},"finish_reason":null}]}""")
            if (terminal.isNotEmpty()) assertFalse(decoder.event(terminal))
            assertEquals(SpeechErrorCode.PROTOCOL, failure { decoder.end() }.code)
        }
        val odd = XiaomiMiMoSpeechDecoder {}
        odd.event("""{"choices":[{"delta":{"audio":{"data":"AQ=="}},"finish_reason":"stop"}]}""")
        assertEquals(SpeechErrorCode.PROTOCOL, failure { odd.end() }.code)
        val empty = XiaomiMiMoSpeechDecoder {}
        empty.event("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")
        assertEquals(SpeechErrorCode.PROTOCOL, failure { empty.end() }.code)
    }

    @Test fun failedFinishAndMalformedAudioCannotBecomeSuccessfulPlayback() {
        for (reason in listOf("length", "content_filter", "unknown")) {
            failure { XiaomiMiMoSpeechDecoder {}.event("""{"choices":[{"delta":{"audio":{"data":"AQI="}},"finish_reason":"$reason"}]}""") }
        }
        for (data in listOf("\"!!\"", "123")) {
            assertEquals(SpeechErrorCode.PROTOCOL, failure {
                XiaomiMiMoSpeechDecoder {}.event("""{"choices":[{"delta":{"audio":{"data":$data}},"finish_reason":null}]}""")
            }.code)
        }
        assertEquals(SpeechErrorCode.PROTOCOL, failure {
            XiaomiMiMoSpeechDecoder {}.event("""{"choices":[{"message":{"audio":{"data":"AQI="}},"finish_reason":"stop"}]}""")
        }.code)
        val error = failure { XiaomiMiMoSpeechDecoder {}.event("""{"error":{"code":401,"message":"sk-private-secret"}}""") }
        assertEquals(SpeechErrorCode.AUTHENTICATION, error.code)
        assertFalse(error.toString().contains("sk-private-secret"))
        assertEquals(SpeechErrorCode.RATE_LIMITED, failure {
            xiaomiMiMoTranscript(JSONObject("""{"error":{"code":429,"message":"private"}}"""))
        }.code)
    }

    private fun body(request: Request): JSONObject {
        val buffer = Buffer(); request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    private fun failure(action: () -> Unit): SpeechFailure = try {
        action(); throw AssertionError("Expected SpeechFailure")
    } catch (error: SpeechFailure) { error }
}
