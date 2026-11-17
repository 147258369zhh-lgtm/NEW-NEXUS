package io.github.mangi.eta.agent.packages

import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStreamReader
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.runtime.AgentBillingTokens

/** A selected package provider has one bounded outbound generation request to the configured origin. */
internal class PackageProviderHttp(private val config: AgentModelClient.ModelConfig, private val controller: AgentRunController,
    private val request: ProviderRequest?, private val started: () -> Unit,
    private val client: okhttp3.OkHttpClient = AgentHttpClient.client,
    private val onUsage: (AgentTokenUsage) -> Unit = {},
) : AutoCloseable {
    @Volatile private var response: okhttp3.Response? = null
    @Volatile private var reader: InputStreamReader? = null
    @Volatile private var call: okhttp3.Call? = null
    private var binding: AgentRunController.ResourceBinding? = null
    private var sent = false
    private var characters = 0
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val received = StringBuilder()
    private var sse = false
    private var terminal = false
    var usage: AgentTokenUsage? = null
        private set
    fun start(args: JSONObject): JSONObject {
        check(!sent && !closed.get()) { "PACKAGE_PROVIDER_SINGLE_REQUEST_ONLY" }
        val target = args.getString("url").toHttpUrl()
        val configured = config.baseUrl.toHttpUrl()
        io.github.mangi.eta.agent.connector.ConnectorSecurityManager().validateEndpoint(target.toString())
        require(target.scheme == configured.scheme && target.host == configured.host && target.port == configured.port) { "插件模型请求地址超出当前提供商" }
        val method = args.optString("method", "GET").uppercase(java.util.Locale.ROOT)
        require(method == if (request == null) "GET" else "POST") { "插件模型请求方式不匹配" }
        val headers = when (val value = args.opt("headers")) { is JSONObject -> value; is String -> JSONObject(value); else -> JSONObject() }
        require(headers.length() <= 64) { "PACKAGE_PROVIDER_HEADER_LIMIT" }
        val builder = Request.Builder().url(target)
        headers.keys().forEach { key ->
            require(key.lowercase() !in setOf("host", "content-length", "connection", "transfer-encoding", "proxy-authorization"))
            builder.header(key, headers.getString(key))
        }
        if (request != null) {
            require(target.encodedPath == configured.encodedPath && target.encodedQuery == configured.encodedQuery) { "插件模型生成请求必须使用配置的地址" }
            val raw = args.opt("body")?.toString() ?: error("PACKAGE_PROVIDER_BODY_REQUIRED")
            require(raw.toByteArray().size <= 256 * 1024)
            val body = if (request.budgetOutputTokenCeiling != null) {
                val endpoint = when {
                    target.encodedPath.endsWith("/chat/completions") -> EndpointKind.CHAT_COMPLETIONS
                    target.encodedPath.endsWith("/responses") -> EndpointKind.RESPONSES
                    target.encodedPath.endsWith("/messages") -> EndpointKind.ANTHROPIC_MESSAGES
                    else -> throw AgentModelFailure("MODEL_COST_BOUND_UNSUPPORTED", false, "此插件接口不能校验费用限额，未发送请求")
                }
                ProviderBudgetOutputLimit.apply(JSONObject(raw), request, endpoint).toString()
            } else raw
            builder.post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        } else {
            require(target.encodedPath.endsWith("/models")) { "插件模型目录请求必须使用 models 地址" }
            builder.get()
        }
        controller.throwIfStopped()
        sent = true; started()
        call = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build().newCall(builder.build())
        binding = controller.register { close() }
        controller.throwIfCancelled()
        val responseValue = requireNotNull(call).execute(); response = responseValue
        if (closed.get()) { responseValue.close(); error("PACKAGE_PROVIDER_HTTP_CLOSED") }
        if (!responseValue.isSuccessful) throw AgentModelFailure.http(responseValue.code, responseValue.peekBody(16_384).string())
        reader = InputStreamReader(requireNotNull(responseValue.body).byteStream(), Charsets.UTF_8)
        sse = responseValue.header("Content-Type").orEmpty().startsWith("text/event-stream", true)
        return JSONObject().put("ok", true).put("statusCode", responseValue.code).put("headers", JSONObject(responseValue.headers.toMap()))
            .put("contentType", responseValue.header("Content-Type"))
    }
    fun next(): JSONObject {
        controller.throwIfStopped()
        if (terminal) return JSONObject().put("ok", true).put("done", true)
        val source = requireNotNull(reader) { "PACKAGE_PROVIDER_HTTP_NOT_OPEN" }
        check(!closed.get()) { "PACKAGE_PROVIDER_HTTP_CLOSED" }
        val buffer = CharArray(8192)
        val count = source.read(buffer)
        if (count < 0) {
            if (sse) parseSse(final = true) else runCatching { JSONObject(received.toString()) }.getOrNull()?.let(::captureUsage)
            return JSONObject().put("ok", true).put("done", true)
        }
        characters += count
        require(characters <= 256 * 1024) { "插件模型响应超过大小限制" }
        val chunk = String(buffer, 0, count)
        received.append(chunk)
        if (sse) parseSse()
        return JSONObject().put("ok", true).put("done", false).put("chunk", chunk)
    }
    fun all(args: JSONObject): JSONObject {
        val info = start(args); val text = StringBuilder()
        while (true) { val chunk = next(); if (chunk.getBoolean("done")) break; text.append(chunk.getString("chunk")); require(text.length <= 48 * 1024) { "插件模型目录过大" } }
        close()
        return JSONObject().put("ok", true).put("data", JSONObject(info.toString()).put("content", text.toString()))
    }
    private fun parseSse(final: Boolean = false) {
        while (true) {
            val newline = received.indexOf("\n")
            if (newline < 0) break
            val line = received.substring(0, newline).trimEnd('\r'); received.delete(0, newline + 1)
            if (line.startsWith("data:")) {
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") terminal = true
                else runCatching { JSONObject(payload) }.getOrNull()?.let {
                    captureUsage(it)
                    if (it.optString("type") in setOf("message_stop", "response.completed", "response.incomplete", "response.failed")) terminal = true
                }
            }
        }
        require(received.length <= 64 * 1024) { "PACKAGE_PROVIDER_SSE_LINE_LIMIT" }
        if (final && received.isNotBlank()) {
            runCatching { JSONObject(received.toString().removePrefix("data:").trim()) }.getOrNull()?.let(::captureUsage)
            received.clear()
        }
    }
    private fun captureUsage(value: JSONObject) {
        val raw = value.optJSONObject("usage") ?: value.optJSONObject("message")?.optJSONObject("usage") ?:
            value.optJSONObject("response")?.optJSONObject("usage") ?: return
        fun number(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
            (raw.opt(key) as? Number)?.toLong()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()
        }
        val previous = usage
        val input = number("prompt_tokens", "input_tokens", "input") ?: previous?.inputTokens
        val output = number("completion_tokens", "output_tokens", "output") ?: previous?.outputTokens
        val cached = number("cache_read_input_tokens", "cached_tokens", "cachedInput") ?: raw.optJSONObject("prompt_tokens_details")?.let {
            (it.opt("cached_tokens") as? Number)?.toInt()?.takeIf { count -> count >= 0 }
        } ?: previous?.cachedTokens
        usage = AgentTokenUsage(inputTokens = input, outputTokens = output, cachedTokens = cached,
            billingBasis = if (raw.has("cache_read_input_tokens") || raw.has("cache_creation_input_tokens")) AgentBillingTokens.ANTHROPIC else AgentBillingTokens.OPENAI,
            cacheWriteTokens = number("cache_creation_input_tokens") ?: previous?.cacheWriteTokens)
        usage?.let(onUsage)
    }
    override fun close() { closed.set(true); call?.cancel(); response?.close(); response = null; reader = null; binding?.close(); binding = null }
}
