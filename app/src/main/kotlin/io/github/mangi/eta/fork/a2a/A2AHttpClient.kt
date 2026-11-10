package io.github.mangi.eta.fork.a2a

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.util.UUID

/** HTTP client for the A2A 1.0 JSON-RPC binding. All URLs pass through [A2APeerPolicy]. */
internal class A2AHttpClient(
    private val policy: A2APeerPolicy,
    baseClient: OkHttpClient = OkHttpClient(),
    private val json: Json = A2A_JSON,
    private val requestIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val client: OkHttpClient = baseClient.newBuilder()
        .dns(policy.secureDns(baseClient.dns))
        .build()

    suspend fun discoverAgentCard(cardUrl: String, bearerToken: String? = null): A2AAgentCard =
        withContext(Dispatchers.IO) {
            val url = policy.validateUrl(cardUrl)
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", A2A_JSON_CONTENT_TYPE)
                .header("A2A-Version", A2A_PROTOCOL_VERSION)
                .applyBearer(bearerToken)
                .build()
            client.newCall(request).execute().use { response ->
                ensureSuccessful(response)
                val body = readBoundedUtf8(response, MAX_AGENT_CARD_BYTES)
                json.decodeFromString<A2AAgentCard>(body).also { card ->
                    if (card.supportedInterfaces.isEmpty()) {
                        throw A2AProtocolException("Agent Card does not declare an interface")
                    }
                    if (card.supportedInterfaces.none { it.protocolVersion == A2A_PROTOCOL_VERSION }) {
                        throw A2AProtocolException("Agent does not support A2A 1.0")
                    }
                }
            }
        }

    suspend fun sendMessage(
        endpointUrl: String,
        request: A2ASendMessageRequest,
        bearerToken: String? = null,
    ): A2ASendMessageResponse {
        val result = rpc(
            endpointUrl = endpointUrl,
            method = "SendMessage",
            params = json.encodeToJsonElement(request),
            bearerToken = bearerToken,
        )
        return json.decodeFromJsonElement(result)
    }

    suspend fun getTask(
        endpointUrl: String,
        request: A2AGetTaskRequest,
        bearerToken: String? = null,
    ): A2ATask {
        val result = rpc(
            endpointUrl = endpointUrl,
            method = "GetTask",
            params = json.encodeToJsonElement(request),
            bearerToken = bearerToken,
        )
        return json.decodeFromJsonElement(result)
    }

    suspend fun cancelTask(
        endpointUrl: String,
        request: A2ACancelTaskRequest,
        bearerToken: String? = null,
    ): A2ATask {
        val result = rpc(
            endpointUrl = endpointUrl,
            method = "CancelTask",
            params = json.encodeToJsonElement(request),
            bearerToken = bearerToken,
        )
        return json.decodeFromJsonElement(result)
    }

    /** Pull fallback for peers without SSE or push notifications. */
    suspend fun waitForTask(
        endpointUrl: String,
        request: A2AGetTaskRequest,
        bearerToken: String? = null,
        timeoutMillis: Long = 60_000L,
        pollIntervalMillis: Long = 500L,
    ): A2ATask {
        require(timeoutMillis > 0L) { "A2A task timeout must be positive" }
        require(pollIntervalMillis in 50L..timeoutMillis) { "A2A poll interval is out of range" }
        return withTimeout(timeoutMillis) {
            while (true) {
                val task = getTask(endpointUrl, request, bearerToken)
                if (task.status.state.isTerminal || task.status.state.isInterrupted) return@withTimeout task
                delay(pollIntervalMillis)
            }
            error("unreachable")
        }
    }

    fun sendStreamingMessage(
        endpointUrl: String,
        request: A2ASendMessageRequest,
        bearerToken: String? = null,
    ): Flow<A2AStreamResponse> = stream(
        endpointUrl = endpointUrl,
        method = "SendStreamingMessage",
        params = json.encodeToJsonElement(request),
        bearerToken = bearerToken,
    )

    fun subscribeToTask(
        endpointUrl: String,
        request: A2AGetTaskRequest,
        bearerToken: String? = null,
    ): Flow<A2AStreamResponse> = stream(
        endpointUrl = endpointUrl,
        method = "SubscribeToTask",
        params = json.encodeToJsonElement(request),
        bearerToken = bearerToken,
    )

    private suspend fun rpc(
        endpointUrl: String,
        method: String,
        params: JsonElement,
        bearerToken: String?,
    ): JsonObject = withContext(Dispatchers.IO) {
        val url = policy.validateUrl(endpointUrl)
        val requestId = requestIdFactory()
        val rpcRequest = A2AJsonRpcRequest(id = requestId, method = method, params = params)
        val request = Request.Builder()
            .url(url)
            .post(json.encodeToString(rpcRequest).toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", A2A_JSON_CONTENT_TYPE)
            .header("A2A-Version", A2A_PROTOCOL_VERSION)
            .applyBearer(bearerToken)
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccessful(response)
            decodeRpcResponse(
                readBoundedUtf8(response, MAX_RPC_RESPONSE_BYTES),
                expectedRequestId = requestId,
            )
        }
    }

    private fun stream(
        endpointUrl: String,
        method: String,
        params: JsonElement,
        bearerToken: String?,
    ): Flow<A2AStreamResponse> = flow {
        val url = policy.validateUrl(endpointUrl)
        val requestId = requestIdFactory()
        val rpcRequest = A2AJsonRpcRequest(id = requestId, method = method, params = params)
        val request = Request.Builder()
            .url(url)
            .post(json.encodeToString(rpcRequest).toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "text/event-stream")
            .header("A2A-Version", A2A_PROTOCOL_VERSION)
            .applyBearer(bearerToken)
            .build()
        client.newCall(request).execute().use { response ->
            ensureSuccessful(response)
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            if (!contentType.startsWith("text/event-stream")) {
                throw A2AProtocolException("A2A streaming response did not use text/event-stream")
            }
            var totalBytes = 0L
            val data = StringBuilder()
            val source = response.body.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                totalBytes += line.toByteArray(Charsets.UTF_8).size + 1L
                if (totalBytes > MAX_STREAM_BYTES) {
                    throw A2AProtocolException("A2A stream exceeded the response size limit")
                }
                if (line.isEmpty()) {
                    if (data.isNotEmpty()) {
                        emit(parseStreamEvent(data.toString(), requestId))
                        data.clear()
                    }
                } else if (line.startsWith("data:")) {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").trimStart())
                }
            }
            if (data.isNotEmpty()) emit(parseStreamEvent(data.toString(), requestId))
        }
    }.flowOn(Dispatchers.IO)

    private fun decodeRpcResponse(raw: String, expectedRequestId: String): JsonObject {
        val response = try {
            json.decodeFromString<A2AJsonRpcResponse>(raw)
        } catch (error: Exception) {
            throw A2AProtocolException("Malformed A2A JSON-RPC response", error)
        }
        if (response.jsonrpc != "2.0") {
            throw A2AProtocolException("A2A response is not JSON-RPC 2.0")
        }
        if (response.id != expectedRequestId) {
            throw A2AProtocolException("A2A response id did not match the request")
        }
        response.error?.let { error ->
            throw A2AProtocolException("A2A ${error.code}: ${error.message}")
        }
        return response.result ?: throw A2AProtocolException("A2A response was empty")
    }

    private fun parseStreamEvent(raw: String, expectedRequestId: String): A2AStreamResponse {
        val result = decodeRpcResponse(raw, expectedRequestId)
        return try {
            json.decodeFromJsonElement(result)
        } catch (error: Exception) {
            throw A2AProtocolException("Malformed A2A stream event", error)
        }
    }

    private fun ensureSuccessful(response: Response) {
        if (!response.isSuccessful) {
            throw A2AProtocolException("A2A HTTP ${response.code} ${response.message}")
        }
    }

    private fun readBoundedUtf8(response: Response, limit: Int): String {
        val body = response.body
        body.contentLength().takeIf { it >= 0 && it > limit }?.let {
            throw A2AProtocolException("A2A response exceeded the size limit")
        }
        return body.byteStream().use { input ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limit) throw A2AProtocolException("A2A response exceeded the size limit")
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }
    }

    private fun Request.Builder.applyBearer(token: String?): Request.Builder = apply {
        token?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
    }

    companion object {
        private const val MAX_AGENT_CARD_BYTES = 2 * 1024 * 1024
        private const val MAX_RPC_RESPONSE_BYTES = 8 * 1024 * 1024
        private const val MAX_STREAM_BYTES = 32L * 1024L * 1024L
        private val JSON_MEDIA_TYPE = A2A_JSONRPC_CONTENT_TYPE.toMediaType()
    }
}

internal val A2A_JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
