package io.github.mangi.eta.agent.capability

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.data.repository.McpCredentialsUnavailable
import io.github.mangi.eta.data.repository.McpSecretStore
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal data class ApiOAuthAuthorizationRequest(
    val connectorId: String,
    val authorizationUrl: String,
)

internal sealed interface ApiOAuthCallbackResult {
    val connectorId: String?
    val message: String

    data class Success(
        override val connectorId: String,
        val connectorName: String,
        override val message: String = "OAuth 授权完成",
    ) : ApiOAuthCallbackResult

    data class Failure(
        override val connectorId: String? = null,
        override val message: String,
    ) : ApiOAuthCallbackResult
}

internal data class ApiOAuthTokenBundle(
    val accessToken: String = "",
    val refreshToken: String? = null,
    val tokenType: String = "Bearer",
    val expiresAt: Long? = null,
    val scope: String? = null,
    val clientSecret: String? = null,
)

/** OAuth 2.0 Authorization Code + PKCE client for imported REST connectors. */
internal class ApiOAuth2Client(
    context: Context,
    baseClient: OkHttpClient = AgentHttpClient.client,
    secretStore: McpSecretStore? = null,
    connectorProvider: ((String) -> ApiConnectorDefinition?)? = null,
) {
    private val appContext = context.applicationContext
    private val secrets = secretStore ?: McpSecretStore(appContext)
    private val loadConnector = connectorProvider ?: { connectorId ->
        ApiConnectorStore(appContext).load().firstOrNull { it.id == connectorId }
    }
    private val resultPreferences = appContext.getSharedPreferences(RESULT_PREFERENCES, Context.MODE_PRIVATE)
    private val client = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun beginAuthorization(connectorId: String): ApiOAuthAuthorizationRequest {
        val connector = connector(connectorId)
        val oauth = requireNotNull(connector.oauth) { "该连接没有 OAuth 授权配置" }
        val bundle = readBundle(connectorId)
        if (oauth.usesClientSecret && bundle?.clientSecret.isNullOrBlank()) {
            throw ApiConnectorException("OAUTH_CLIENT_SECRET_MISSING", "请先配置 OAuth Client Secret")
        }
        val state = randomToken(32)
        val verifier = randomToken(48)
        val pending = JSONObject()
            .put("connectorId", connector.id)
            .put("state", state)
            .put("verifier", verifier)
            .put("createdAt", System.currentTimeMillis())
        secrets.setBearerToken(PENDING_KEY, pending.toString())
        val url = requireNotNull(oauth.authorizationEndpoint.toHttpUrlOrNull()) {
            "OAuth 授权地址无效"
        }.newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", oauth.clientId)
            .addQueryParameter("redirect_uri", ApiOAuth2Configuration.REDIRECT_URI)
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", pkceChallenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .apply {
                oauth.scopes.takeIf(List<String>::isNotEmpty)?.let { scopes ->
                    addQueryParameter("scope", scopes.joinToString(" "))
                }
            }
            .build()
        return ApiOAuthAuthorizationRequest(connector.id, url.toString())
    }

    fun setClientSecret(connectorId: String, secret: String) {
        val connector = connector(connectorId)
        require(connector.authType == ApiConnectorAuthType.OAUTH2) {
            "只有 OAuth 连接可以保存 Client Secret"
        }
        val normalized = secret.trim()
        if (normalized.length > MAX_CLIENT_SECRET_LENGTH || normalized.any(Char::isISOControl)) {
            throw ApiConnectorException("INVALID_CLIENT_SECRET", "OAuth Client Secret 格式无效")
        }
        val current = readBundle(connectorId) ?: ApiOAuthTokenBundle()
        writeBundle(connectorId, current.copy(clientSecret = normalized.takeIf(String::isNotBlank)))
    }

    fun credentialAvailable(connectorId: String): Boolean {
        val connector = connectorOrNull(connectorId) ?: return false
        val bundle = readBundle(connectorId) ?: return false
        val hasToken = bundle.accessToken.isNotBlank() || !bundle.refreshToken.isNullOrBlank()
        val hasSecret = connector.oauth?.usesClientSecret != true || !bundle.clientSecret.isNullOrBlank()
        return hasToken && hasSecret
    }

    fun accessToken(connector: ApiConnectorDefinition, forceRefresh: Boolean = false): String {
        require(connector.authType == ApiConnectorAuthType.OAUTH2) { "连接未使用 OAuth" }
        val oauth = requireNotNull(connector.oauth) { "OAuth 配置缺失" }
        val bundle = readBundle(connector.id)
            ?: throw ApiConnectorException("OAUTH_NOT_AUTHORIZED", "该 API 连接尚未完成 OAuth 授权")
        val now = System.currentTimeMillis()
        val canUseAccess = bundle.accessToken.isNotBlank() &&
            (bundle.expiresAt == null || bundle.expiresAt > now + EXPIRY_SKEW_MILLIS)
        if (!forceRefresh && canUseAccess) return bundle.accessToken
        val refreshToken = bundle.refreshToken?.takeIf(String::isNotBlank)
            ?: throw ApiConnectorException("OAUTH_REFRESH_MISSING", "OAuth 授权已过期，请重新授权")
        if (oauth.usesClientSecret && bundle.clientSecret.isNullOrBlank()) {
            throw ApiConnectorException("OAUTH_CLIENT_SECRET_MISSING", "OAuth Client Secret 不可用，请重新配置")
        }
        val refreshed = exchangeToken(
            oauth = oauth,
            fields = linkedMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to oauth.clientId,
            ),
            clientSecret = bundle.clientSecret,
            previousRefreshToken = bundle.refreshToken,
        )
        writeBundle(connector.id, refreshed)
        return refreshed.accessToken
    }

    fun completeAuthorization(callback: Uri?): ApiOAuthCallbackResult {
        val result = try {
            completeAuthorizationUnchecked(callback)
        } catch (failure: McpCredentialsUnavailable) {
            ApiOAuthCallbackResult.Failure(message = "授权凭据不可用，请重新打开 Nexus 后再试")
        } catch (failure: Throwable) {
            ApiOAuthCallbackResult.Failure(
                message = failure.message?.lineSequence()?.firstOrNull()?.take(300)
                    ?: "OAuth 授权未完成",
            )
        }
        publishResult(result)
        return result
    }

    fun consumeLastResult(): ApiOAuthCallbackResult? {
        callbackEventsInternal.value = null
        val raw = resultPreferences.getString(LAST_RESULT_KEY, null) ?: return null
        resultPreferences.edit().remove(LAST_RESULT_KEY).apply()
        return runCatching {
            val value = JSONObject(raw)
            if (value.optBoolean("success", false)) {
                ApiOAuthCallbackResult.Success(
                    connectorId = value.optString("connectorId"),
                    connectorName = value.optString("connectorName"),
                    message = value.optString("message", "OAuth 授权完成"),
                )
            } else {
                ApiOAuthCallbackResult.Failure(
                    connectorId = value.optString("connectorId").takeIf(String::isNotBlank),
                    message = value.optString("message", "OAuth 授权未完成"),
                )
            }
        }.getOrNull()
    }

    fun clear(connectorId: String) {
        runCatching { secrets.clear(apiConnectorSecretKey(connectorId)) }
        val pendingConnector = runCatching {
            secrets.bearerToken(PENDING_KEY)
                ?.let(::JSONObject)
                ?.optString("connectorId")
        }.getOrNull()
        if (pendingConnector == connectorId) runCatching { secrets.clear(PENDING_KEY) }
    }

    private fun completeAuthorizationUnchecked(callback: Uri?): ApiOAuthCallbackResult {
        if (callback == null || callback.scheme != "eta" || callback.host != "capability-oauth" ||
            callback.path != "/callback"
        ) {
            throw ApiConnectorException("INVALID_OAUTH_CALLBACK", "OAuth 回调地址无效")
        }
        val rawPending = secrets.bearerToken(PENDING_KEY)
            ?: throw ApiConnectorException("OAUTH_SESSION_MISSING", "OAuth 授权会话已过期，请重新发起授权")
        val pending = runCatching { JSONObject(rawPending) }
            .getOrElse { throw ApiConnectorException("OAUTH_SESSION_INVALID", "OAuth 授权会话无效") }
        val connectorId = pending.optString("connectorId")
        val expectedState = pending.optString("state")
        val callbackState = callback.getQueryParameter("state").orEmpty()
        if (expectedState.isBlank() || !constantTimeEquals(expectedState, callbackState)) {
            return ApiOAuthCallbackResult.Failure(connectorId, "OAuth 回调状态不匹配，已拒绝本次授权")
        }
        val createdAt = pending.optLong("createdAt", 0L)
        val age = System.currentTimeMillis() - createdAt
        if (createdAt <= 0L || age !in 0..MAX_AUTHORIZATION_AGE_MILLIS) {
            secrets.clear(PENDING_KEY)
            return ApiOAuthCallbackResult.Failure(connectorId, "OAuth 授权会话已过期，请重新发起授权")
        }
        val connector = connector(connectorId)
        val oauth = requireNotNull(connector.oauth) { "OAuth 配置缺失" }
        callback.getQueryParameter("error")?.let { error ->
            secrets.clear(PENDING_KEY)
            return ApiOAuthCallbackResult.Failure(
                connectorId = connector.id,
                message = oauthErrorMessage(error),
            )
        }
        val code = callback.getQueryParameter("code")
            ?: throw ApiConnectorException("OAUTH_CODE_MISSING", "授权回调缺少 code")
        if (code.length > MAX_CODE_LENGTH || code.any(Char::isISOControl)) {
            throw ApiConnectorException("OAUTH_CODE_INVALID", "授权回调 code 格式无效")
        }
        val current = readBundle(connector.id)
        if (oauth.usesClientSecret && current?.clientSecret.isNullOrBlank()) {
            throw ApiConnectorException("OAUTH_CLIENT_SECRET_MISSING", "OAuth Client Secret 不可用，请重新配置")
        }
        return try {
            val verifier = pending.optString("verifier")
            if (verifier.length !in MIN_VERIFIER_LENGTH..MAX_VERIFIER_LENGTH) {
                throw ApiConnectorException("OAUTH_VERIFIER_INVALID", "OAuth PKCE 校验信息无效")
            }
            val tokens = exchangeToken(
                oauth = oauth,
                fields = linkedMapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to ApiOAuth2Configuration.REDIRECT_URI,
                    "client_id" to oauth.clientId,
                    "code_verifier" to verifier,
                ),
                clientSecret = current?.clientSecret,
                previousRefreshToken = current?.refreshToken,
            ).copy(clientSecret = current?.clientSecret)
            writeBundle(connector.id, tokens)
            secrets.clear(PENDING_KEY)
            ApiOAuthCallbackResult.Success(connector.id, connector.name)
        } catch (failure: Throwable) {
            runCatching { secrets.clear(PENDING_KEY) }
            throw failure
        }
    }

    private fun exchangeToken(
        oauth: ApiOAuth2Configuration,
        fields: Map<String, String>,
        clientSecret: String?,
        previousRefreshToken: String?,
    ): ApiOAuthTokenBundle {
        val form = FormBody.Builder().apply {
            fields.forEach(::add)
            if (oauth.usesClientSecret && !clientSecret.isNullOrBlank()) add("client_secret", clientSecret)
        }.build()
        val request = Request.Builder()
            .url(oauth.tokenEndpoint)
            .header("Accept", "application/json")
            .header("User-Agent", "Nexus-Capability-Center/1")
            .post(form)
            .build()
        return client.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                throw ApiConnectorException("OAUTH_REDIRECT", "OAuth 令牌端点返回重定向，已拒绝")
            }
            val declared = response.body.contentLength()
            if (declared > MAX_TOKEN_RESPONSE_BYTES) {
                throw ApiConnectorException("OAUTH_RESPONSE_TOO_LARGE", "OAuth 令牌响应超过大小限制")
            }
            val bytes = response.body.byteStream().use(::readBounded)
            val body = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
            if (!response.isSuccessful || body == null) {
                throw ApiConnectorException(
                    "OAUTH_TOKEN_REJECTED",
                    body?.optString("error")
                        ?.takeIf { OAUTH_ERROR_CODE.matches(it) }
                        ?.let(::oauthErrorMessage)
                        ?: "OAuth 授权服务器拒绝了令牌请求（HTTP ${response.code}）",
                )
            }
            val accessToken = body.optString("access_token").trim()
            if (accessToken.isBlank() || accessToken.length > MAX_ACCESS_TOKEN_LENGTH ||
                accessToken.any(Char::isISOControl)
            ) {
                throw ApiConnectorException("OAUTH_ACCESS_TOKEN_INVALID", "OAuth 令牌响应缺少有效 access_token")
            }
            val tokenType = body.optString("token_type", "Bearer").trim()
            if (!tokenType.equals("Bearer", ignoreCase = true)) {
                throw ApiConnectorException("OAUTH_TOKEN_TYPE_UNSUPPORTED", "目前只支持 Bearer OAuth 访问令牌")
            }
            val refreshToken = body.optString("refresh_token").trim()
                .takeIf(String::isNotBlank)
                ?: previousRefreshToken?.takeIf(String::isNotBlank)
            val expiresIn = numericLong(body.opt("expires_in"))
                ?.takeIf { it in 1..MAX_EXPIRES_IN_SECONDS }
            ApiOAuthTokenBundle(
                accessToken = accessToken,
                refreshToken = refreshToken?.takeIf { it.length <= MAX_REFRESH_TOKEN_LENGTH },
                tokenType = "Bearer",
                expiresAt = expiresIn?.let { System.currentTimeMillis() + it * 1_000L },
                scope = body.optString("scope").trim()
                    .take(MAX_SCOPE_RESPONSE_LENGTH)
                    .takeIf(String::isNotBlank),
                clientSecret = clientSecret,
            )
        }
    }

    private fun readBundle(connectorId: String): ApiOAuthTokenBundle? {
        val raw = try {
            secrets.bearerToken(apiConnectorSecretKey(connectorId))
        } catch (_: McpCredentialsUnavailable) {
            throw ApiConnectorException("OAUTH_CREDENTIAL_UNAVAILABLE", "OAuth 凭据不可用，请重新授权")
        } ?: return null
        if (!raw.startsWith("{")) return ApiOAuthTokenBundle(accessToken = raw)
        val value = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return ApiOAuthTokenBundle(
            accessToken = value.optString("accessToken"),
            refreshToken = value.optString("refreshToken").takeIf(String::isNotBlank),
            tokenType = value.optString("tokenType", "Bearer"),
            expiresAt = value.optLong("expiresAt", 0L).takeIf { it > 0L },
            scope = value.optString("scope").takeIf(String::isNotBlank),
            clientSecret = value.optString("clientSecret").takeIf(String::isNotBlank),
        )
    }

    private fun writeBundle(connectorId: String, bundle: ApiOAuthTokenBundle) {
        val hasValue = bundle.accessToken.isNotBlank() || !bundle.refreshToken.isNullOrBlank() ||
            !bundle.clientSecret.isNullOrBlank()
        if (!hasValue) {
            secrets.clear(apiConnectorSecretKey(connectorId))
            return
        }
        val value = JSONObject()
            .put("accessToken", bundle.accessToken)
            .put("refreshToken", bundle.refreshToken ?: JSONObject.NULL)
            .put("tokenType", bundle.tokenType)
            .put("expiresAt", bundle.expiresAt ?: JSONObject.NULL)
            .put("scope", bundle.scope ?: JSONObject.NULL)
            .put("clientSecret", bundle.clientSecret ?: JSONObject.NULL)
        secrets.setBearerToken(apiConnectorSecretKey(connectorId), value.toString())
    }

    private fun connector(connectorId: String): ApiConnectorDefinition =
        connectorOrNull(connectorId) ?: throw ApiConnectorException("CONNECTOR_NOT_FOUND", "API 连接不存在")

    private fun connectorOrNull(connectorId: String): ApiConnectorDefinition? = loadConnector(connectorId)

    private fun publishResult(result: ApiOAuthCallbackResult) {
        val value = JSONObject()
            .put("success", result is ApiOAuthCallbackResult.Success)
            .put("connectorId", result.connectorId ?: "")
            .put("connectorName", (result as? ApiOAuthCallbackResult.Success)?.connectorName.orEmpty())
            .put("message", result.message.take(300))
            .put("createdAt", System.currentTimeMillis())
        resultPreferences.edit().putString(LAST_RESULT_KEY, value.toString()).commit()
        callbackEventsInternal.value = result
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_TOKEN_RESPONSE_BYTES) {
                throw ApiConnectorException("OAUTH_RESPONSE_TOO_LARGE", "OAuth 令牌响应超过大小限制")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun numericLong(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

    private fun randomToken(bytes: Int): String =
        ByteArray(bytes).also(SECURE_RANDOM::nextBytes).base64Url()

    private fun pkceChallenge(verifier: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
            .base64Url()

    private fun ByteArray.base64Url(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private fun constantTimeEquals(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            actual.toByteArray(Charsets.US_ASCII),
        )

    private fun oauthErrorMessage(code: String): String = when (code.trim()) {
        "invalid_request" -> "OAuth 请求参数无效"
        "invalid_client" -> "OAuth 客户端认证失败"
        "invalid_grant" -> "OAuth 授权码或刷新令牌已失效"
        "unauthorized_client" -> "OAuth 客户端无权使用此授权方式"
        "unsupported_grant_type" -> "OAuth 服务不支持此授权方式"
        "invalid_scope" -> "OAuth Scope 无效"
        "access_denied" -> "你已取消 OAuth 授权"
        "server_error" -> "OAuth 服务暂时不可用"
        "temporarily_unavailable" -> "OAuth 服务暂时不可用"
        else -> "OAuth 服务拒绝了授权请求"
    }

    companion object {
        private const val RESULT_PREFERENCES = "eta_capability_oauth"
        private const val LAST_RESULT_KEY = "lastResult"
        private const val PENDING_KEY = "api_oauth_pending_v1"
        private const val MAX_AUTHORIZATION_AGE_MILLIS = 15L * 60L * 1_000L
        private const val EXPIRY_SKEW_MILLIS = 60L * 1_000L
        private const val MAX_TOKEN_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_CLIENT_SECRET_LENGTH = 16_384
        private const val MAX_ACCESS_TOKEN_LENGTH = 16_384
        private const val MAX_REFRESH_TOKEN_LENGTH = 16_384
        private const val MAX_CODE_LENGTH = 8_192
        private const val MAX_SCOPE_RESPONSE_LENGTH = 8_192
        private const val MAX_EXPIRES_IN_SECONDS = 366L * 24L * 60L * 60L
        private const val MIN_VERIFIER_LENGTH = 43
        private const val MAX_VERIFIER_LENGTH = 128
        private val OAUTH_ERROR_CODE = Regex("[a-z0-9_.-]{1,80}")
        private val SECURE_RANDOM = SecureRandom()
        private val callbackEventsInternal = MutableStateFlow<ApiOAuthCallbackResult?>(null)
        val callbackEvents = callbackEventsInternal.asStateFlow()
    }
}
