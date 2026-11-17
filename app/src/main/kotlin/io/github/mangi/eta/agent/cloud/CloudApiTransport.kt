package io.github.mangi.eta.agent.cloud

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject

/** The caller resolves an already authorized attachment/project reference, never an arbitrary path. */
internal data class CloudInputFile(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long? = null,
    val open: () -> InputStream,
)

internal class CloudToolContext(
    val androidContext: Context,
    val controller: AgentRunController,
    val resolveInput: (String) -> CloudInputFile,
    val saveOutput: suspend (name: String, mimeType: String, bytes: ByteArray) -> JSONObject,
    val scopeKey: String = controller.conversationId ?: controller.executionTargetId ?: "local",
)

internal data class CloudBinaryResponse(val bytes: ByteArray, val mimeType: String)

/** Messages intentionally exclude provider response text, URLs, headers and request payloads. */
internal class CloudApiException(
    val code: String,
    val httpStatus: Int? = null,
    val outcomeUnknown: Boolean = false,
) : IOException("云服务请求未完成：$code") {
    fun toJson(): JSONObject = JSONObject().put("ok", false).put("error", code)
        .put("http_status", httpStatus ?: JSONObject.NULL).put("outcome_unknown", outcomeUnknown)
        .put("retry_safe", !outcomeUnknown)
}

/** No automatic retry: a disconnected upload may already be billed by the provider. */
internal class CloudApiTransport(private val client: OkHttpClient = OkHttpClient()) {
    fun json(
        controller: AgentRunController,
        method: String,
        url: String,
        body: JSONObject? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Int = 180,
        maxResponseBytes: Long = 8L * MIB,
        requireJsonContentType: Boolean = false,
    ): JSONObject {
        val requestBody = body?.toString()?.toByteArray(Charsets.UTF_8)?.also {
            if (it.size > MAX_JSON_REQUEST_BYTES) throw CloudApiException("request_too_large")
        }?.toRequestBody(JSON)
        val response = exchange(controller, request(method, url, requestBody, headers), timeoutSeconds, maxResponseBytes)
        if (requireJsonContentType && response.mimeType.substringBefore(';').trim().lowercase() !in
            setOf("application/json", "application/problem+json")) {
            throw CloudApiException("invalid_json_content_type", outcomeUnknown = method !in setOf("GET", "HEAD"))
        }
        return parseJson(response.bytes)
    }

    /** fields are inserted before file, as required by DeepSeek Files. */
    fun multipart(
        controller: AgentRunController,
        url: String,
        fields: Map<String, String>,
        file: CloudInputFile,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Int = 180,
        maxFileBytes: Long = 100L * MIB,
    ): JSONObject {
        require(maxFileBytes in 1..100L * MIB)
        if (file.sizeBytes != null && file.sizeBytes > maxFileBytes) throw CloudApiException("file_too_large")
        val streamBody = object : RequestBody() {
            override fun contentType() = file.mimeType.toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                file.open().use { input ->
                    val binding = controller.register { runCatching { input.close() } }
                    try {
                        val buffer = ByteArray(32 * 1024)
                        var count = 0L
                        while (true) {
                            controller.throwIfCancelled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > maxFileBytes) throw CloudApiException("file_too_large", outcomeUnknown = true)
                            sink.write(buffer, 0, read)
                        }
                    } finally { binding.close() }
                }
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (name, value) -> addFormDataPart(name, value) }
            addFormDataPart("file", file.name.replace(Regex("[\\r\\n\\\"]"), "_"), streamBody)
        }.build()
        return parseJson(exchange(controller, request("POST", url, multipart, headers), timeoutSeconds, 8L * MIB).bytes)
    }

    fun bytes(
        controller: AgentRunController,
        method: String,
        url: String,
        body: JSONObject? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Int = 180,
        maxResponseBytes: Long = 64L * MIB,
    ): CloudBinaryResponse {
        val encoded = body?.toString()?.toByteArray(Charsets.UTF_8)?.also {
            if (it.size > MAX_JSON_REQUEST_BYTES) throw CloudApiException("request_too_large")
        }
        val result = exchange(controller, request(method, url, encoded?.toRequestBody(JSON), headers), timeoutSeconds, maxResponseBytes)
        if (result.mimeType.substringBefore(';').trim().equals("application/json", true)) {
            parseJson(result.bytes)
            throw CloudApiException("expected_binary_response", outcomeUnknown = method !in setOf("GET", "HEAD"))
        }
        return result
    }

    fun form(
        controller: AgentRunController,
        url: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Int = 180,
    ): JSONObject {
        val builder = okhttp3.FormBody.Builder()
        fields.forEach { (key, value) -> builder.add(key, value) }
        return parseJson(exchange(controller, request("POST", url, builder.build(), headers), timeoutSeconds, 8L * MIB).bytes)
    }

    private fun request(method: String, url: String, body: RequestBody?, headers: Map<String, String>): Request {
        val builder = Request.Builder().url(url)
        val endpoint = builder.build().url
        if (!endpoint.isHttps) throw CloudApiException("https_required")
        if (endpoint.username.isNotBlank() || endpoint.password.isNotBlank() || endpoint.fragment != null) {
            throw CloudApiException("invalid_endpoint")
        }
        headers.forEach { (name, value) -> builder.header(name, value) }
        val payload = body ?: if (method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
        // OkHttp may otherwise replay a POST after HTTP 503 Retry-After: 0 even when connection
        // retry is disabled. One-shot bodies prohibit every follow-up replay of billed work.
        val oneShot = payload?.let { delegate -> object : RequestBody() {
            override fun contentType() = delegate.contentType()
            override fun contentLength() = delegate.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
        } }
        return builder.method(method, oneShot).build()
    }

    private fun exchange(
        controller: AgentRunController,
        request: Request,
        timeoutSeconds: Int,
        maxResponseBytes: Long,
    ): CloudBinaryResponse {
        require(timeoutSeconds in 1..600 && maxResponseBytes in 1..128L * MIB)
        controller.throwIfCancelled()
        val call = client.newBuilder().retryOnConnectionFailure(false).followRedirects(false)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES).cache(null)
            .authenticator(okhttp3.Authenticator.NONE).proxyAuthenticator(okhttp3.Authenticator.NONE)
            .followSslRedirects(false).callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .connectTimeout(minOf(timeoutSeconds, 30).toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS).build().newCall(request)
        val binding = controller.register(call::cancel)
        val effectful = request.method !in setOf("GET", "HEAD")
        try {
            return call.execute().use { response ->
                controller.throwIfStopped()
                if (!response.isSuccessful) {
                    // Even 5xx can follow completed work; no implicit resubmission/failover.
                    throw CloudApiException("http_${response.code}", response.code,
                        effectful && response.code >= 500)
                }
                val responseBody = response.body ?: throw CloudApiException("empty_response", outcomeUnknown = effectful)
                if (responseBody.contentLength() > maxResponseBytes) throw CloudApiException("response_too_large", outcomeUnknown = effectful)
                val buffer = ByteArray(32 * 1024)
                val bytes = ByteArrayOutputStream()
                responseBody.byteStream().use { input ->
                    while (true) {
                        controller.throwIfCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (bytes.size().toLong() + count > maxResponseBytes) throw CloudApiException("response_too_large", outcomeUnknown = effectful)
                        bytes.write(buffer, 0, count)
                    }
                }
                CloudBinaryResponse(bytes.toByteArray(), responseBody.contentType()?.toString() ?: "application/octet-stream")
            }
        } catch (failure: IOException) {
            controller.throwIfStopped()
            throw if (failure is CloudApiException) failure else CloudApiException("network_error", outcomeUnknown = effectful)
        } finally { binding.close() }
    }

    internal companion object {
        const val MIB = 1024L * 1024L
        private const val MAX_JSON_REQUEST_BYTES = 48L * MIB
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun parseJson(bytes: ByteArray): JSONObject {
            val json = try { JSONObject(bytes.toString(Charsets.UTF_8)) }
            catch (_: Exception) { throw CloudApiException("invalid_json", outcomeUnknown = true) }
            val error = json.opt("error")
            if ((error != null && error != JSONObject.NULL && error.toString().isNotBlank()) || json.optInt("error_code", 0) != 0 ||
                (json.has("code") && json.optString("code").isNotBlank() && json.optString("code") !in setOf("0", "200", "Success", "SUCCESS")) ||
                json.optJSONObject("Response")?.optJSONObject("Error") != null) {
                throw CloudApiException("provider_rejected")
            }
            return json
        }
    }
}

/** Coroutine cancellation closes controller-bound calls as well as stopping before the first call. */
@OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
internal suspend fun <T> CloudToolContext.cancellableCloudIo(block: suspend () -> T): T = withContext(Dispatchers.IO) {
    currentCoroutineContext().ensureActive()
    val coroutineBinding = currentCoroutineContext().job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
        if (it is kotlinx.coroutines.CancellationException) controller.cancel()
    }
    try { controller.throwIfCancelled(); block() } finally { coroutineBinding.dispose() }
}
