package io.github.mangi.eta.fork.a2a

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.URI
import java.util.Base64

@Serializable
internal data class A2APairingData(
    val version: Int = CURRENT_A2A_PAIRING_VERSION,
    val displayName: String = "",
    val agentCardUrl: String,
    val bearerToken: String? = null,
)

internal const val CURRENT_A2A_PAIRING_VERSION = 1

/** Portable, explicitly user-triggered connection data for setting up another A2A client. */
internal object A2APairingCode {
    private const val PREFIX = "eta-a2a://pair?payload="
    private const val MAX_CODE_LENGTH = 12_000
    private const val MAX_URL_LENGTH = 4_096
    private const val MAX_TOKEN_LENGTH = 4_096
    private const val MAX_NAME_LENGTH = 80

    fun encode(displayName: String, agentCardUrl: String, bearerToken: String?): String {
        val payload = A2APairingData(
            displayName = validateDisplayName(displayName),
            agentCardUrl = validateCardUrl(agentCardUrl),
            bearerToken = validateToken(bearerToken),
        )
        val json = A2A_JSON.encodeToString(payload).toByteArray(Charsets.UTF_8)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json)
    }

    fun decode(raw: String): A2APairingData {
        val code = raw.trim()
        require(code.length in 1..MAX_CODE_LENGTH) { "连接信息为空或过长" }
        require(code.startsWith(PREFIX)) { "不是 Nexus A2A 配对连接信息" }
        val encoded = code.removePrefix(PREFIX)
        require(encoded.isNotBlank() && encoded.length <= MAX_CODE_LENGTH) { "连接信息载荷无效" }
        val payload = runCatching {
            val json = Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
            A2A_JSON.decodeFromString<A2APairingData>(json)
        }.getOrElse { failure ->
            throw A2AProtocolException("连接信息损坏或格式不正确", failure)
        }
        if (payload.version != CURRENT_A2A_PAIRING_VERSION) {
            throw A2AProtocolException("不支持此 A2A 配对版本")
        }
        return payload.copy(
            displayName = validateDisplayName(payload.displayName),
            agentCardUrl = validateCardUrl(payload.agentCardUrl),
            bearerToken = validateToken(payload.bearerToken),
        )
    }

    private fun validateDisplayName(value: String): String {
        val normalized = value.trim()
        require(normalized.length <= MAX_NAME_LENGTH) { "A2A 名称过长" }
        require(normalized.none(Char::isISOControl)) { "A2A 名称包含无效字符" }
        return normalized
    }

    private fun validateCardUrl(value: String): String {
        val normalized = value.trim()
        require(normalized.length in 1..MAX_URL_LENGTH) { "Agent Card 地址为空或过长" }
        require(normalized.none(Char::isWhitespace)) { "Agent Card 地址不能包含空白字符" }
        val uri = runCatching { URI(normalized) }.getOrNull()
            ?: throw A2AProtocolException("Agent Card 地址格式无效")
        require(uri.scheme == "http" || uri.scheme == "https") { "Agent Card 必须使用 HTTP 或 HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Agent Card 地址缺少主机名" }
        require(uri.userInfo == null) { "Agent Card 地址不能嵌入账号或密码" }
        require(uri.fragment == null) { "Agent Card 地址不能包含片段" }
        return normalized
    }

    private fun validateToken(value: String?): String? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty)
        require(normalized == null || normalized.length <= MAX_TOKEN_LENGTH) { "Bearer Token 过长" }
        require(normalized == null || normalized.none(Char::isISOControl)) { "Bearer Token 包含无效字符" }
        return normalized
    }
}
