package io.github.mangi.eta.agent.cloud

import android.app.Application
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.*
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** All requests use an in-memory HTTP interceptor; no external API is contacted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [CloudAtomicFileRenameShadow::class])
class OnlineImageGenerationTest {
    @Test fun geminiImageUsesNativeGenerationRequest() {
        val image = Model(id = "image", modelId = "gemini-2.5-flash-image", displayName = "Image",
            outputModalities = listOf("text", "image"))
        val provider = GeminiProviderSetting(id = "gemini", name = "Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta", apiKey = "fixture", models = listOf(image))
        val candidate = OnlineImageGeneration.candidates(listOf(provider)).single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent",
            OnlineImageGeneration.geminiEndpoint(candidate))
        val request = OnlineImageGeneration.geminiRequest(JSONObject().put("prompt", "a tree"))
        assertEquals("a tree", request.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("IMAGE", request.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(1))
        assertTrue(runCatching { OnlineImageGeneration.geminiRequest(JSONObject().put("prompt", "tree").put("n", 2)) }.isFailure)
    }
    @Test fun normalProviderImageSelectionRequiresKnownProtocolAndUnambiguousModel() {
        val image = Model(id = "image-id", modelId = "gpt-image-1", displayName = "Image",
            outputModalities = listOf("image"))
        val provider = OpenAiCompatibleProviderSetting(id = "provider", name = "OpenAI", baseUrl = "https://api.openai.com/v1",
            sourceType = ProviderSourceTypes.OPENAI, apiKey = "fixture", models = listOf(image))
        assertEquals(image, OnlineImageGeneration.select(listOf(provider), JSONObject())?.model)
        assertNull(OnlineImageGeneration.select(listOf(provider.copy(isEnabled = false)), JSONObject()))
        assertEquals(1, OnlineImageGeneration.candidates(listOf(provider.copy(sourceType = ProviderSourceTypes.CUSTOM))).size)
        assertEquals(1, OnlineImageGeneration.candidates(listOf(provider.copy(sourceType = ProviderSourceTypes.CUSTOM,
            baseUrl = "https://unknown.example/v1"))).size)
        assertTrue(OnlineImageGeneration.candidates(listOf(provider.copy(sourceType = ProviderSourceTypes.CUSTOM,
            baseUrl = "https://unknown.example/v1", models = listOf(image.copy(outputModalities = listOf("text")))))).isEmpty())
        assertTrue(runCatching { OnlineImageGeneration.select(listOf(provider, provider.copy(id = "other")), JSONObject()) }.isFailure)
        assertEquals("provider", OnlineImageGeneration.select(listOf(provider, provider.copy(id = "other")),
            JSONObject().put("provider_id", "provider"))?.provider?.id)
        val bailian = provider.copy(sourceType = ProviderSourceTypes.BAILIAN,
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            models = listOf(image.copy(modelId = "qwen-image-2.0-pro")))
        assertEquals("https://dashscope.aliyuncs.com", OnlineImageGeneration.bailianBase(bailian))
        assertTrue(OnlineImageGeneration.candidateValid(OnlineImageGeneration.candidates(listOf(bailian)).single()))
        assertFalse(OnlineImageGeneration.candidateValid(OnlineImageGeneration.candidates(
            listOf(bailian.copy(baseUrl = "https://untrusted.example/v1"))).single()))
        assertNull(OnlineImageGeneration.select(listOf(bailian.copy(baseUrl = "https://untrusted.example/v1")), JSONObject()))
        assertNull(OnlineImageGeneration.select(listOf(provider.copy(apiKey = "bad\nkey")), JSONObject()))
    }

    @Test fun unknownExplicitProviderNeverFallsBackToAnotherPaidRoute() = runBlocking {
        val image = Model(id = "image", modelId = "gpt-image-1", displayName = "Image", outputModalities = listOf("image"))
        val provider = OpenAiCompatibleProviderSetting(id = "configured", name = "OpenAI", baseUrl = "https://api.openai.com/v1",
            sourceType = ProviderSourceTypes.OPENAI, apiKey = "fixture", models = listOf(image))
        val config = CloudToolsRuntimeConfig(CloudToolsSettings(enabled = true), CloudToolsCredentials(), listOf(provider))
        val context = CloudToolContext(RuntimeEnvironment.getApplication(), AgentRunController(), { error("No input") },
            { _, _, _ -> error("No output") }, "no-fallback-${UUID.randomUUID()}")
        val result = CloudMediaTools.executeConfigured(context, JSONObject().put("operation", "image_generate")
            .put("provider_id", "missing").put("prompt", "tree").put("request_id", "request-${UUID.randomUUID()}"), config)
        assertEquals("image_model_not_found", result.getString("error"))
    }

    @Test fun unknownExplicitModelNeverFallsBackToAnotherPaidRoute() = runBlocking {
        val runtime = CloudToolsRuntimeConfig(CloudToolsSettings(enabled = true), CloudToolsCredentials())
        val context = CloudToolContext(RuntimeEnvironment.getApplication(), AgentRunController(), { error("No input") },
            { _, _, _ -> error("No output") }, "no-fallback-${UUID.randomUUID()}")
        val result = CloudMediaTools.executeConfigured(context, JSONObject().put("operation", "image_generate")
            .put("model", "missing-image").put("prompt", "tree").put("request_id", "request-${UUID.randomUUID()}"), runtime)
        assertEquals("image_model_not_found", result.getString("error"))
    }

    @Test fun invalidProviderDoesNotFallBackToAnotherImageService() = runBlocking {
        val image = Model("image", "qwen-image", "Image", outputModalities = listOf("image"))
        val provider = OpenAiCompatibleProviderSetting(id = "invalid", name = "Invalid",
            baseUrl = "https://untrusted.example/v1", sourceType = ProviderSourceTypes.BAILIAN,
            apiKey = "fixture", models = listOf(image))
        val runtime = CloudToolsRuntimeConfig(CloudToolsSettings(enabled = true, onlineImage = online),
            CloudToolsCredentials(onlineImageApiKey = "legacy-key"), listOf(provider))
        assertEquals("cloud_configuration_invalid", CloudToolAvailability.unavailableCode("cloud_media", "image_generate", runtime))
        val context = CloudToolContext(RuntimeEnvironment.getApplication(), AgentRunController(), { error("No input") },
            { _, _, _ -> error("No output") }, "no-fallback-${UUID.randomUUID()}")
        val result = CloudMediaTools.executeConfigured(context, JSONObject().put("operation", "image_generate")
            .put("prompt", "tree").put("request_id", "request-${UUID.randomUUID()}"), runtime)
        assertEquals("image_model_not_found", result.getString("error"))
    }

    @Test fun providerImageAvailabilityRejectsInvalidRouteLimits() {
        val image = Model("image", "gpt-image-1", "Image", outputModalities = listOf("image"))
        val provider = CustomProviderSetting("custom", "Custom", "https://example.invalid/v1",
            apiKey = "fixture", models = listOf(image))
        val runtime = CloudToolsRuntimeConfig(CloudToolsSettings(routes = mapOf("image_generate" to
            CloudCapabilityRoute(primary = "bailian", timeoutSeconds = 0))), CloudToolsCredentials(), listOf(provider))
        assertEquals("cloud_configuration_invalid", CloudToolAvailability.unavailableCode("cloud_media", "image_generate", runtime))
    }

    @Test fun selectedProviderTakesPriorityOverLegacyImageConfiguration() = runBlocking {
        val model = Model("manual", "custom-image", "Image", outputModalities = listOf("text", "image"))
        val provider = CustomProviderSetting(id = "custom", name = "Custom", baseUrl = "https://custom.example/v1",
            apiKey = "custom-key", models = listOf(model))
        val runtime = CloudToolsRuntimeConfig(CloudToolsSettings(onlineImage = online),
            CloudToolsCredentials(onlineImageApiKey = "legacy-key"), listOf(provider))
        val transport = CloudApiTransport(OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("custom.example", chain.request().url.host)
            assertEquals("Bearer custom-key", chain.request().header("Authorization"))
            val body = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            assertEquals("custom-image", JSONObject(body).getString("model"))
            val response = JSONObject().put("data", JSONArray().put(JSONObject().put("b64_json", Base64.getEncoder().encodeToString(png))))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build())
        val context = CloudToolContext(RuntimeEnvironment.getApplication(), AgentRunController(), { error("No input") },
            { _, _, _ -> JSONObject().put("ok", true).put("artifact_id", "manual-image") }, "manual-${UUID.randomUUID()}")
        val result = CloudMediaTools.executeConfigured(context, JSONObject().put("operation", "image_generate")
            .put("provider_id", "custom").put("model", "custom-image").put("prompt", "tree")
            .put("request_id", "request-${UUID.randomUUID()}"), runtime, transport)
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals("manual-image", result.getJSONArray("files").getJSONObject(0).getString("artifact_id"))
    }
    private val online = OnlineImageGenerationConfig(enabled = true, baseUrl = "https://images.example.invalid/v1", model = "fixture-image")
    private val config get() = CloudToolsRuntimeConfig(CloudToolsSettings(onlineImage = online),
        CloudToolsCredentials(onlineImageApiKey = "fixture-key"))
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aTioAAAAASUVORK5CYII=")

    @Test fun geminiResponsesPublishImagesAndRejectInvalidResults() = runBlocking {
        val model = Model("image", "gemini-2.5-flash-image", "Image", outputModalities = listOf("text", "image"))
        val provider = GeminiProviderSetting(id = "gemini", name = "Gemini", apiKey = "fixture-gemini", models = listOf(model))
        val runtime = CloudToolsRuntimeConfig(CloudToolsSettings(enabled = true), CloudToolsCredentials(), listOf(provider))
        val image = Base64.getEncoder().encodeToString(png)
        fun response(part: JSONObject): String = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Here it is")).put(part))))).toString()
        val examples = listOf(
            response(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/png").put("data", image))) to true,
            response(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/png").put("data", image))) to true,
            response(JSONObject().put("text", "No image")) to false,
            response(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/png").put("data", "bad!"))) to false,
            "{\"error\":{\"message\":\"failed\"}}" to false,
        )
        for ((body, expected) in examples) {
            val saves = AtomicInteger()
            val transport = CloudApiTransport(OkHttpClient.Builder().addInterceptor { chain ->
                assertEquals("/v1beta/models/gemini-2.5-flash-image:generateContent", chain.request().url.encodedPath)
                assertEquals("fixture-gemini", chain.request().header("x-goog-api-key"))
                assertNull(chain.request().header("Authorization"))
                val code = if (body.contains("\"error\"")) 400 else 200
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }.build())
            val context = CloudToolContext(RuntimeEnvironment.getApplication(), AgentRunController(), { error("No input") },
                { _, mime, bytes -> saves.incrementAndGet(); assertEquals("image/png", mime); assertArrayEquals(png, bytes)
                    JSONObject().put("ok", true).put("artifact_id", "gemini-artifact") }, "gemini-${UUID.randomUUID()}")
            val result = CloudMediaTools.executeConfigured(context, JSONObject().put("operation", "image_generate")
                .put("prompt", "tree").put("request_id", "request-${UUID.randomUUID()}"), runtime, transport)
            assertEquals(result.toString(), expected, result.optBoolean("ok"))
            assertEquals(if (expected) 1 else 0, saves.get())
            if (expected) assertEquals("gemini-artifact", result.getJSONArray("files").getJSONObject(0).getString("artifact_id"))
        }
    }

    @Test fun explicitImageModelIsAvailableWhileOtherCloudToolsRemainDisabled() {
        assertNull(CloudToolAvailability.unavailableCode("cloud_media", "image_generate", config))
        assertEquals("cloud_tools_disabled", CloudToolAvailability.unavailableCode("cloud_media", "video_generate", config))
        assertEquals("cloud_credentials_missing", CloudToolAvailability.unavailableCode("cloud_media", "image_generate",
            CloudToolsRuntimeConfig(config.settings, CloudToolsCredentials())))
    }

    @Test fun defaultProtocolOmitsUnsupportedLegacyResponseFormatAndNormalizesEndpoint() {
        assertEquals("https://images.example.invalid/v1/images/generations", OnlineImageGeneration.endpoint(online))
        assertEquals("https://images.example.invalid/v1/images/generations", OnlineImageGeneration.endpoint(
            online.copy(baseUrl = "https://images.example.invalid/v1/images/generations/")))
        val body = OnlineImageGeneration.request(JSONObject().put("prompt", "a tree").put("size", "1024*1024"), online)
        assertFalse(body.has("response_format"))
        assertEquals("1024x1024", body.getString("size"))
        assertEquals("b64_json", OnlineImageGeneration.request(JSONObject().put("prompt", "a tree"),
            online.copy(responseFormat = "b64_json")).getString("response_format"))
        assertTrue(runCatching { OnlineImageGeneration.endpoint(online.copy(protocol = "unknown")) }.isFailure)
    }

    @Test fun generationPublishesImageReceiptAndReplayNeverBillsAgain() = runBlocking {
        val calls = AtomicInteger(); val saves = AtomicInteger()
        val transport = CloudApiTransport(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            assertEquals("/v1/images/generations", chain.request().url.encodedPath)
            assertEquals("Bearer fixture-key", chain.request().header("Authorization"))
            val body = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            assertEquals("fixture-image", JSONObject(body).getString("model"))
            val response = JSONObject().put("data", JSONArray().put(JSONObject().put("b64_json", Base64.getEncoder().encodeToString(png))))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody("application/json".toMediaType())).build()
        }.build())
        val context = CloudToolContext(RuntimeEnvironment.getApplication(), AgentRunController(),
            { error("No input") }, { _, mime, bytes ->
                saves.incrementAndGet(); assertEquals("image/png", mime); assertArrayEquals(png, bytes)
                JSONObject().put("ok", true).put("artifact_id", "fixture-artifact").put("mime_type", mime)
                    .put("content_uri", "content://fixture/image")
            }, "image-scope-${UUID.randomUUID()}")
        val args = JSONObject().put("operation", "image_generate").put("prompt", "a tree")
            .put("request_id", "request-${UUID.randomUUID()}")
        val first = CloudMediaTools.executeConfigured(context, args, config, transport)
        val replay = CloudMediaTools.executeConfigured(context, args, config, transport)
        assertTrue(first.toString(), first.getBoolean("ok"))
        assertEquals("fixture-artifact", first.getJSONArray("files").getJSONObject(0).getString("artifact_id"))
        assertTrue(replay.getBoolean("reused_request"))
        assertEquals(1, calls.get()); assertEquals(1, saves.get())
    }

    @Test fun ambiguousNetworkFailureCannotResubmitPaidGeneration() = runBlocking {
        val calls = AtomicInteger()
        val transport = CloudApiTransport(OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet(); throw java.io.IOException("fixture timeout")
        }.build())
        val context = CloudToolContext(RuntimeEnvironment.getApplication(), AgentRunController(), { error("No input") },
            { _, _, _ -> error("No output") }, "unknown-image-${UUID.randomUUID()}")
        val args = JSONObject().put("operation", "image_generate").put("prompt", "a tree").put("request_id", "request-${UUID.randomUUID()}")
        assertTrue(CloudMediaTools.executeConfigured(context, args, config, transport).getBoolean("outcome_unknown"))
        assertEquals("request_outcome_unknown", CloudMediaTools.executeConfigured(context, args, config, transport).getString("error"))
        assertEquals(1, calls.get())
    }

    @Test fun wrongResponseContentTypeCannotBeTreatedAsAValidPaidResultOrRetried() = runBlocking {
        val calls = AtomicInteger()
        val transport = CloudApiTransport(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{}".toResponseBody("text/html".toMediaType())).build()
        }.build())
        val context = CloudToolContext(RuntimeEnvironment.getApplication(), AgentRunController(), { error("No input") },
            { _, _, _ -> error("No output") }, "content-type-${UUID.randomUUID()}")
        val args = JSONObject().put("operation", "image_generate").put("prompt", "a tree").put("request_id", "request-${UUID.randomUUID()}")
        assertEquals("invalid_json_content_type", CloudMediaTools.executeConfigured(context, args, config, transport).getString("error"))
        assertEquals("request_outcome_unknown", CloudMediaTools.executeConfigured(context, args, config, transport).getString("error"))
        assertEquals(1, calls.get())
    }

    @Test fun unsupportedOrUnboundedImagePayloadsFailClosed() {
        assertTrue(runCatching { OnlineImageGeneration.decodeImage("not base64", 1024) }.isFailure)
        assertTrue(runCatching { OnlineImageGeneration.decodeImage(Base64.getEncoder().encodeToString("<svg/>".toByteArray()), 1024) }.isFailure)
        assertTrue(runCatching { OnlineImageGeneration.decodeImage(Base64.getEncoder().encodeToString(png), 4) }.isFailure)
        listOf("http://example.invalid/a", "https://127.0.0.1/a", "https://169.254.169.254/a", "https://key@example.invalid/a").forEach {
            assertTrue(it, runCatching { CloudMediaDownload.validatedPublicImageUrl(it) }.isFailure)
        }
    }
}
