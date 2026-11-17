package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.TencentSpeechConfig
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.supportsLiveRecognition
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TencentSpeechTest {
    @Test fun signatureMatchesIndependentHmacFixtureAndKeysStayOffUrl() {
        val request = tencentSpeechRequest("tts", "TextToVoice", "2019-08-23", TencentSpeechConfig(),
            "test-id", "test-secret", JSONObject(), 1551113065)
        assertEquals("https://tts.tencentcloudapi.com/", request.url.toString())
        assertEquals("TC3-HMAC-SHA256 Credential=test-id/2019-02-25/tts/tc3_request, SignedHeaders=content-type;host, Signature=b45c056283c9a9a64ceb888948703b1d02f620c48f8a173fdbad9d654ed31561",
            request.header("Authorization"))
        assertFalse(request.url.toString().contains("test-secret"))
        assertEquals("1551113065", request.header("X-TC-Timestamp"))
        assertEquals("ap-shanghai", request.header("X-TC-Region"))
    }

    @Test fun shortRecognitionUsesActualWavLengthAndRejectsInvalidAudio() {
        val wav = pcmToWav(ByteArray(32000))
        val body = tencentRecognitionBody(TencentSpeechConfig(), wav)
        assertEquals(wav.size, body.getInt("DataLen"))
        assertArrayEquals(wav, java.util.Base64.getDecoder().decode(body.getString("Data")))
        assertEquals("16k_zh", body.getString("EngSerViceType"))
        assertEquals(SpeechErrorCode.AUDIO, failure { tencentRecognitionBody(TencentSpeechConfig(), pcmToWav(ByteArray(32000), 24000)) }.code)
        assertEquals(SpeechErrorCode.AUDIO, failure { tencentRecognitionBody(TencentSpeechConfig(), pcmToWav(ByteArray(32000 * 60 + 2))) }.code)
    }

    @Test fun chunksPreserveUnicodeAndFitBothLanguageLimits() {
        val text = "汉字🚲。".repeat(90)
        val chunks = tencentSpeechChunks(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.codePointCount(0, it.length) <= 80 })
        assertFalse(AsrProvider.TENCENT.supportsLiveRecognition())
        assertFalse(AsrProvider.BAIDU.supportsLiveRecognition())
        assertTrue(AsrProvider.IFLYTEK.supportsLiveRecognition())
    }

    @Test fun serverErrorsDoNotExposeEchoedCredentials() {
        val error = failure { tencentSpeechResponse(JSONObject().put("Response", JSONObject().put("Error",
            JSONObject().put("Code", "AuthFailure.SignatureFailure").put("Message", "private-secret")))) }
        assertEquals(SpeechErrorCode.AUTHENTICATION, error.code)
        assertFalse(error.message.orEmpty().contains("private-secret"))
        assertEquals(SpeechErrorCode.PROTOCOL, failure { tencentSpeechResponse(JSONObject()) }.code)
        assertEquals(SpeechErrorCode.CONFIGURATION, failure { validateTencentSpeechKeys("i\nd", "key") }.code)
    }

    @Test fun oldSettingsAndDomesticProfilesRoundTrip() {
        val old = Json.decodeFromString<SpeechSettings>("{\"asr\":\"DOUBAO\",\"qwenVoice\":\"Cherry\"}")
        assertEquals(AsrProvider.DOUBAO, old.asr)
        for (provider in listOf(AsrProvider.BAIDU, AsrProvider.IFLYTEK, AsrProvider.TENCENT)) {
            val settings = old.copy(asr = provider)
            assertEquals(settings, Json.decodeFromString<SpeechSettings>(Json.encodeToString(settings)))
        }
    }

    private fun failure(block: () -> Unit): SpeechFailure {
        try { block(); fail("Expected SpeechFailure") } catch (error: SpeechFailure) { return error }
        error("unreachable")
    }
}
