package io.github.mangi.eta.agent.connector

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.web.WebHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Generic REST shares ETA's bounded HTTP transport; no ambient cookies or auth. */
internal class RestConnectorTransport(
    private val endpoint: String,
    private val bearerToken: String?,
    private val security: ConnectorSecurityManager,
    baseClient: okhttp3.OkHttpClient = AgentHttpClient.client,
) : ConnectorTransport {
    private val http = WebHttpTransport(baseClient)
    override val descriptor = ConnectorDescriptor("rest:$endpoint", "REST", ConnectorKind.REST, "http-rest",
        listOf(ConnectorCapability("request", "HTTP 请求", "发送有界 REST 请求", destructive = true)))
    override suspend fun probe(): ConnectorHealth = runCatching {
        security.validateEndpoint(endpoint)
        ConnectorHealth(true, "REST 地址有效")
    }.getOrElse { ConnectorHealth(false, it.message ?: "REST 地址无效") }

    override suspend fun invoke(request: ConnectorInvocation): ConnectorResult = withContext(Dispatchers.IO) {
        security.authorize(descriptor, request, request.arguments.optBoolean("confirmed"))
        security.validateEndpoint(endpoint)
        val args = request.arguments
        require(!args.optBoolean("ignore_ssl")) { "不允许忽略 TLS 证书校验" }
        require(!args.optBoolean("stream")) { "此请求为有界 HTTP，流式事件请使用 SSE 连接" }
        val headers = args.optJSONObject("headers") ?: JSONObject()
        require(headers.length() <= 64)
        val values = headers.keys().asSequence().associateWith { key ->
            require(key.lowercase() !in setOf("host", "content-length", "transfer-encoding", "connection", "proxy-authorization")) { "不允许的请求头" }
            headers.getString(key)
        }.toMutableMap()
        bearerToken?.takeIf(String::isNotBlank)?.let { values["Authorization"] = "Bearer $it" }
        val body = if (args.has("body")) args.getString("body").toByteArray(Charsets.UTF_8) else null
        require((body?.size ?: 0) <= 256 * 1024) { "HTTP 请求正文超过 256 KiB" }
        val response = http.request(endpoint, args.optString("method", "GET"), values, body,
            args.optString("content_type").takeIf(String::isNotBlank), maxBytes = args.optInt("max_bytes", 48 * 1024).coerceIn(1, 256 * 1024),
            followRedirects = false, includeErrorBody = true)
        ConnectorResult(JSONObject().put("statusCode", response.status).put("content", response.bytes.toString(Charsets.UTF_8))
            .put("contentType", response.contentType ?: JSONObject.NULL).put("size", response.bytes.size)
            .put("headers", JSONObject(response.headers)).put("truncated", response.truncated).put("url", response.finalUrl),
            success = response.status in 200..299,
            errorCode = if (response.status in 200..299) null else "HTTP_${response.status}",
            errorMessage = if (response.status in 200..299) null else "REST HTTP ${response.status}")
    }
    override fun close() = http.close()
}
