package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.AzureSpeechConfig
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.model.supportsLiveRecognition
import kotlinx.serialization.json.Json
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AzureSpeechTest {
    @Test fun shortAudioUsesResourceKeyAndLanguageWithoutLeakingKeyIntoUrl() {
        val wav = pcmToWav(ByteArray(32_000))
        val request = azureRecognitionRequest(AzureSpeechConfig(), " test-key ", wav)
        assertEquals("eastasia.stt.speech.microsoft.com", request.url.host)
        assertEquals("zh-CN", request.url.queryParameter("language"))
        assertEquals("simple", request.url.queryParameter("format"))
        assertEquals("test-key", request.header("Ocp-Apim-Subscription-Key"))
        assertEquals("audio/pcm", request.body!!.contentType()!!.parameter("codecs"))
        assertFalse(request.url.toString().contains("test-key"))
        val buffer = Buffer(); request.body!!.writeTo(buffer)
        assertArrayEquals(wav, buffer.readByteArray())
    }

    @Test fun recognitionRejectsOversizedAndWrongSampleRateBeforeSending() {
        assertEquals(SpeechErrorCode.AUDIO, failure {
            azureRecognitionRequest(AzureSpeechConfig(), "test", pcmToWav(ByteArray(32_000 * 60 + 2)))
        }.code)
        assertEquals(SpeechErrorCode.AUDIO, failure {
            azureRecognitionRequest(AzureSpeechConfig(), "test", pcmToWav(ByteArray(100), 24_000))
        }.code)
    }

    @Test fun synthesisEscapesUserTextAndRequestsExistingPlaybackFormat() {
        val request = azureSynthesisRequest(AzureSpeechConfig(), "test-key", "<voice name='other'>A&B</voice>")
        assertEquals("eastasia.tts.speech.microsoft.com", request.url.host)
        assertEquals("raw-24khz-16bit-mono-pcm", request.header("X-Microsoft-OutputFormat"))
        val body = Buffer(); request.body!!.writeTo(body)
        val ssml = body.readUtf8()
        assertTrue(ssml.contains("&lt;voice name=&apos;other&apos;&gt;A&amp;B&lt;/voice&gt;"))
        assertEquals(1, Regex("<voice ").findAll(ssml).count())
    }

    @Test fun forgedHostAndSsmlAttributesAreRejected() {
        listOf("eastasia.attacker.invalid", "eastasia/path", "EastAsia", "eastasia@host").forEach { region ->
            assertEquals(SpeechErrorCode.CONFIGURATION, failure {
                azureSynthesisRequest(AzureSpeechConfig(region = region), "test-key", "hello")
            }.code)
        }
        assertEquals(SpeechErrorCode.CONFIGURATION, failure {
            azureSynthesisRequest(AzureSpeechConfig(voice = "voice'><audio src='https://host'/>"), "test-key", "hello")
        }.code)
        assertEquals(SpeechErrorCode.CONFIGURATION, failure { validateAzureSpeechKey("key\nvalue") }.code)
    }

    @Test fun silentAndFailedRecognitionNeverBecomeTranscripts() {
        assertEquals("你好", azureTranscript(JSONObject("{\"RecognitionStatus\":\"Success\",\"DisplayText\":\" 你好 \"}")))
        assertEquals(SpeechErrorCode.NO_SPEECH, failure { azureTranscript(JSONObject("{\"RecognitionStatus\":\"NoMatch\"}")) }.code)
        assertEquals(SpeechErrorCode.PROTOCOL, failure { azureTranscript(JSONObject("{}")) }.code)
    }

    @Test fun legacyPreferencesAndKeysSurviveAddingAzure() {
        val json = Json { encodeDefaults = true }
        val old = json.decodeFromString<SpeechSettings>("{\"asr\":\"QWEN_FLASH\",\"tts\":\"DOUBAO\",\"duplexConversation\":true}")
        assertEquals(AsrProvider.QWEN_FLASH, old.asr)
        assertTrue(old.duplexConversation)
        assertFalse(old.asr.supportsLiveRecognition())
        assertFalse(AsrProvider.AZURE.supportsLiveRecognition())
        assertFalse(supportsSpeechSession(TtsProvider.AZURE))
        assertTrue(AsrProvider.DOUBAO.supportsLiveRecognition())
        val secrets = SpeechCredentials(qwenAsr = "qwen", doubaoTts = "doubao")
            .withValue(SpeechCredentialField.AZURE_ASR, "azure-asr")
            .withValue(SpeechCredentialField.AZURE_TTS, "azure-tts")
        val restored = json.decodeFromString<SpeechCredentials>(json.encodeToString(secrets))
        assertEquals("qwen", restored.qwenAsr)
        assertEquals("doubao", restored.doubaoTts)
        assertEquals("azure-asr", restored.azureAsr)
        assertEquals("azure-tts", restored.azureTts)
        assertFalse(restored.toString().contains("azure-asr"))
    }

    private fun failure(action: () -> Unit): SpeechFailure = try {
        action(); throw AssertionError("Expected SpeechFailure")
    } catch (error: SpeechFailure) { error }
}
