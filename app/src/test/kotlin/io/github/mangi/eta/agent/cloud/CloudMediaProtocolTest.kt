package io.github.mangi.eta.agent.cloud

import io.github.mangi.eta.agent.voice.pcmToWav
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudMediaProtocolTest {
    @Test fun textToImageUsesCurrentWanMultimodalSchemaAndExplicitLowCount() {
        val request = CloudMediaProtocol.image(JSONObject().put("prompt", "a forest"), "wan2.6-t2i", emptyList(), false)
        assertEquals(CloudMediaProtocol.MULTIMODAL, request.path)
        assertFalse(request.asynchronous)
        val content = request.body.getJSONObject("input").getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("a forest", content.getJSONObject(0).getString("text"))
        assertEquals(1, request.body.getJSONObject("parameters").getInt("n"))
        assertFalse(request.body.getJSONObject("input").has("prompt"))
    }

    @Test fun qwenEditingRetainsEveryReferenceInInputOrder() {
        val request = CloudMediaProtocol.image(JSONObject().put("prompt", "replace the background"),
            "qwen-image-2.0-pro", listOf("data:image/png;base64,first", "data:image/png;base64,second"), true)
        val content = request.body.getJSONObject("input").getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("data:image/png;base64,first", content.getJSONObject(0).getString("image"))
        assertEquals("data:image/png;base64,second", content.getJSONObject(1).getString("image"))
        assertEquals("replace the background", content.getJSONObject(2).getString("text"))
        assertFalse(request.body.getJSONObject("parameters").has("enable_interleave"))
    }

    @Test fun asyncWanUsesTheCurrentImageGenerationEndpoint() {
        val request = CloudMediaProtocol.image(JSONObject().put("prompt", "a tree").put("async", true), "wan2.6-t2i", emptyList(), false)
        assertEquals("/api/v1/services/aigc/image-generation/generation", request.path)
        assertTrue(request.asynchronous)
        assertTrue(runCatching { CloudMediaProtocol.image(JSONObject().put("prompt", "edit").put("async", true),
            "qwen-image-2.0-pro", listOf("image"), true) }.isFailure)
    }

    @Test fun billedCountsAndDurationsMustBeIntegerValuesWithinTheModelRange() {
        assertTrue(runCatching { CloudMediaProtocol.image(JSONObject().put("prompt", "a tree").put("n", 1.5), "wan2.6-t2i", emptyList(), false) }.isFailure)
        assertTrue(runCatching { CloudMediaProtocol.video(JSONObject().put("prompt", "a tree").put("duration", 16), "wan2.7-t2v", null) }.isFailure)
        assertTrue(runCatching { CloudMediaProtocol.video(JSONObject().put("prompt", "a tree").put("duration", 2.5), "wan2.7-t2v", null) }.isFailure)
        assertTrue(runCatching { CloudMediaProtocol.image(JSONObject().put("prompt", "tree").put("size", "1*1"), "wan2.6-t2i", emptyList(), false) }.isFailure)
    }

    @Test fun textAndImageVideoUseDistinctModelAndAspectSchemas() {
        val args = JSONObject().put("prompt", "the tree moves").put("duration", 3).put("ratio", "9:16")
        val text = CloudMediaProtocol.video(args, "wan2.7-t2v", null)
        assertTrue(text.asynchronous)
        assertEquals("9:16", text.body.getJSONObject("parameters").getString("ratio"))
        val image = CloudMediaProtocol.video(args, "wan2.6-i2v-flash", "data:image/png;base64,reference")
        assertEquals("data:image/png;base64,reference", image.body.getJSONObject("input").getString("img_url"))
        assertFalse(image.body.getJSONObject("parameters").has("ratio"))
        assertTrue(runCatching { CloudMediaProtocol.video(args, "wan2.7-t2v", "image") }.isFailure)
    }

    @Test fun videoUnderstandingUsesRealVideoContentAndSamplingControls() {
        val request = CloudMediaProtocol.videoUnderstanding(JSONObject().put("prompt", "explain").put("fps", 0.5), "qwen3-vl-plus", "data:video/mp4;base64,fixture")
        val part = request.body.getJSONArray("messages").getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("video_url", part.getString("type"))
        assertEquals(0.5, part.getDouble("fps"), 0.0)
        assertEquals("data:video/mp4;base64,fixture", part.getJSONObject("video_url").getString("url"))
        assertTrue(runCatching { CloudMediaProtocol.videoUnderstanding(JSONObject().put("prompt", "explain").put("fps", 0), "qwen3-vl-plus", "video") }.isFailure)
    }

    @Test fun imageAndVideoResponsesReadActualResultFields() {
        val image = JSONObject().put("output", JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
            JSONObject().put("content", JSONArray().put(JSONObject().put("image", "https://a.oss-cn-beijing.aliyuncs.com/a.png")))))))
        assertEquals(listOf("https://a.oss-cn-beijing.aliyuncs.com/a.png"), CloudMediaProtocol.outputUrls(image, false))
        val legacy = JSONObject().put("output", JSONObject().put("results", JSONArray().put(JSONObject().put("url", "https://a.oss-cn-beijing.aliyuncs.com/a.png"))))
        assertEquals(CloudMediaProtocol.outputUrls(image, false), CloudMediaProtocol.outputUrls(legacy, false))
        val video = JSONObject().put("output", JSONObject().put("video_url", "https://a.oss-accelerate.aliyuncs.com/a.mp4"))
        assertEquals(listOf("https://a.oss-accelerate.aliyuncs.com/a.mp4"), CloudMediaProtocol.outputUrls(video, true))
    }

    @Test fun resultDownloadUrlsCannotRedirectToArbitraryHostsOrCarryCredentials() {
        assertEquals("dashscope-result-sh.oss-accelerate.aliyuncs.com", CloudMediaDownload.validatedResultUrl("https://dashscope-result-sh.oss-accelerate.aliyuncs.com/a.mp4?Expires=1").host)
        listOf("http://a.oss-cn-beijing.aliyuncs.com/a", "https://a.oss-cn-beijing.aliyuncs.com.evil.test/a",
            "https://evil.test/a", "https://a.oss-cn-beijing.aliyuncs.com:444/a", "https://key@a.oss-cn-beijing.aliyuncs.com/a",
            "https://127.0.0.1/a", "file:///tmp/a").forEach { assertTrue(it, runCatching { CloudMediaDownload.validatedResultUrl(it) }.isFailure) }
    }

    @Test fun speechUsesVoiceIdWavAndCountsHanCharactersBeforeBilling() {
        val body = CloudMediaProtocol.speech(JSONObject().put("text", "hello 世界").put("voice", "tide"))
        assertEquals("tide", body.getString("voice_id"))
        assertEquals("wav", body.getString("format"))
        assertEquals(10, CloudMediaProtocol.billableCharacters("hello 世界"))
        assertTrue(runCatching { CloudMediaProtocol.speech(JSONObject().put("text", "汉".repeat(5001))) }.isFailure)
        assertTrue(runCatching { CloudMediaProtocol.speech(JSONObject().put("text", "hello").put("voice", "unknown")) }.isFailure)
    }

    @Test fun asrRejectsUnsupportedFormatBitDepthAndExcessDurationBeforeUpload() {
        val wav = pcmToWav(ByteArray(8000 * 2), 8000)
        assertEquals("audio/wav", CloudMediaTools.validateAsr(wav))
        assertTrue(runCatching { CloudMediaTools.validateAsr(wav.copyOf(wav.size - 3)) }.isFailure)
        val unsupported = wav.copyOf().also { it[34] = 8 }
        assertTrue(runCatching { CloudMediaTools.validateAsr(unsupported) }.isFailure)
        assertTrue(runCatching { CloudMediaTools.validateAsr(pcmToWav(ByteArray(8000 * 2 * 121), 8000)) }.isFailure)
        assertTrue(runCatching { CloudMediaTools.validateAsr("not audio".toByteArray()) }.isFailure)
    }

    @Test fun mediaContentSniffingDoesNotMistakeHeicOrAudioMp4ForVideo() {
        val mp4 = ByteArray(20).also { "ftypisom".toByteArray().copyInto(it, 4) }
        val heic = ByteArray(20).also { "ftypheic".toByteArray().copyInto(it, 4) }
        val audio = ByteArray(20).also { "ftypM4A ".toByteArray().copyInto(it, 4) }
        assertEquals("video/mp4", CloudMediaTools.detectedInputMime(mp4))
        assertEquals("application/octet-stream", CloudMediaTools.detectedInputMime(heic))
        assertEquals("application/octet-stream", CloudMediaTools.detectedInputMime(audio))
    }
}
