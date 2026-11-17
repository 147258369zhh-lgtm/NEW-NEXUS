package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.DeepSeekSpeechConfig
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.EtaPortableSecrets
import kotlinx.serialization.json.Json
import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeepSeekSpeechTest {
    @Test fun recognitionUsesOneShotWavMultipartAndIndependentCredentials() {
        val wav = pcmToWav(byteArrayOf(1, 2, 3, 4))
        val request = deepSeekRecognitionRequest(DeepSeekSpeechConfig(baseUrl = "https://api.deepseek.com/v1/"), " voice-key ", wav)
        assertEquals("https://api.deepseek.com/v1/tools/asr", request.url.toString())
        assertEquals("Bearer voice-key", request.header("Authorization"))
        assertNull(request.url.query)
        assertTrue(request.body!!.isOneShot())
        val wire = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(wire.contains("name=\"file\"; filename=\"speech.wav\""))
        assertTrue(wire.contains("Content-Type: audio/wav"))
        assertFalse(wire.contains("voice-key"))
        assertThrows(SpeechFailure::class.java) {
            validateSpeechSettings(SpeechSettings(asr = AsrProvider.DEEPSEEK), SpeechCredentials(qwenAsr = "other-key"), false)
        }
        validateSpeechSettings(SpeechSettings(asr = AsrProvider.DEEPSEEK), SpeechCredentials(deepSeekAsr = "voice-key"), false)
    }

    @Test fun recognitionChecksPcmSizeDurationAndSuccessfulStringTranscript() {
        for (wav in listOf(ByteArray(44), pcmToWav(ByteArray(3)), pcmToWav(ByteArray(16_000 * 2 * 121)),
            ByteArray(8 * 1024 * 1024 + 1), pcmToWav(byteArrayOf(1, 2)).also { it[34] = 8 })) {
            assertEquals(SpeechErrorCode.AUDIO, assertThrows(SpeechFailure::class.java) {
                deepSeekRecognitionRequest(DeepSeekSpeechConfig(), "test-key", wav)
            }.code)
        }
        assertEquals("你好", deepSeekTranscript(JSONObject().put("text", " 你好 ")))
        for (response in listOf(JSONObject(), JSONObject().put("text", 123),
            JSONObject().put("text", "").put("error", JSONObject().put("message", "private-token")))) {
            val error = assertThrows(SpeechFailure::class.java) { deepSeekTranscript(response) }
            assertFalse(error.toString().contains("private-token"))
        }
    }

    @Test fun synthesisUsesOfficialVoiceWavSchemaAndLegacyDefaultsRemainCompatible() {
        val request = deepSeekSynthesisRequest(DeepSeekSpeechConfig(voice = "tide"), "tts-key", "你好 & text")
        assertEquals("https://api.deepseek.com/v1/tools/tts", request.url.toString())
        assertTrue(request.body!!.isOneShot())
        val body = body(request)
        assertEquals("你好 & text", body.getString("text"))
        assertEquals("tide", body.getString("voice_id"))
        assertEquals("wav", body.getString("format"))
        assertTrue(body.getBoolean("filter_markdown"))
        assertThrows(SpeechFailure::class.java) {
            deepSeekSynthesisRequest(DeepSeekSpeechConfig(voice = "custom"), "tts-key", "text")
        }
        assertThrows(SpeechFailure::class.java) {
            deepSeekSynthesisRequest(DeepSeekSpeechConfig(), "tts-key", "中".repeat(5001))
        }
        assertThrows(SpeechFailure::class.java) {
            deepSeekSynthesisRequest(DeepSeekSpeechConfig(baseUrl = "https://example.com/?key=private"), "tts-key", "text")
        }
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val old = json.decodeFromString<SpeechCredentials>("""{"qwenAsr":"old-key"}""")
        assertEquals("old-key", old.qwenAsr)
        assertEquals("", old.deepSeekAsr)
        assertEquals("", old.deepSeekTts)
        assertTrue(EtaPortableSecrets(speechCredentials = SpeechCredentials(deepSeekAsr = "asr-only")).hasCredentials())
        assertTrue(EtaPortableSecrets(speechCredentials = SpeechCredentials(deepSeekTts = "tts-only")).hasCredentials())
        val settings = json.decodeFromString<SpeechSettings>("""{"tts":"DOUBAO"}""")
        assertEquals(TtsProvider.DOUBAO, settings.tts)
        assertEquals(DeepSeekSpeechConfig(), settings.deepSeekTts)
    }

    private fun body(request: Request): JSONObject = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
}
