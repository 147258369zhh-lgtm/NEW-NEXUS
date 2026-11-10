package io.github.mangi.eta.agent.social

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

internal data class SocialInboundMessage(
    val platform: SocialPlatform,
    val externalUserId: String,
    val text: String,
    val timestamp: Long,
    val messageId: String = "",
)

internal object SocialWebhook {
    fun verifySignature(secret: String, body: String, signature: String): Boolean {
        if (secret.length < 16 || signature.isBlank()) return false
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), algorithm)) }
        val expected = mac.doFinal(body.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return MessageDigest.isEqual(expected.toByteArray(), signature.removePrefix("sha256=").lowercase().toByteArray())
    }

    fun parse(platform: SocialPlatform, body: String): SocialInboundMessage {
        val json = JSONObject(body)
        val user = json.optString("user_id").ifBlank { json.optString("sender_id") }
        val text = json.optString("text").ifBlank { json.optString("message") }
        val timestamp = json.optLong("timestamp", System.currentTimeMillis())
        val messageId = json.optString("message_id")
            .ifBlank { json.optString("update_id") }
            .ifBlank { json.optString("id") }
        require(user.isNotBlank() && text.isNotBlank()) { "社交消息缺少用户或文本" }
        require(text.length <= 8_000) { "社交消息过长" }
        require(messageId.length <= 256) { "社交消息 ID 无效" }
        return SocialInboundMessage(platform, user, text, timestamp, messageId)
    }
}

internal enum class SocialInboundDisposition { ACCEPT, DUPLICATE, STALE, INVALID }

internal data class SocialRoutedMessage(
    val message: SocialInboundMessage,
    val allowedTools: Set<String>,
)

/** In-memory replay guard shared by webhook adapters; it never stores message bodies. */
internal class SocialInboundDeduplicator(
    private val maxEntries: Int = 512,
    private val ttlMillis: Long = 24 * 60 * 60 * 1_000L,
) {
    private val seen = LinkedHashMap<String, Long>(maxEntries, 0.75f, true)

    @Synchronized
    fun accept(key: String, now: Long = System.currentTimeMillis()): Boolean {
        seen.entries.removeIf { now - it.value > ttlMillis }
        if (key.isBlank() || seen.containsKey(key)) return false
        if (seen.size >= maxEntries) seen.entries.iterator().let { if (it.hasNext()) it.next().also { _ -> it.remove() } }
        seen[key] = now
        return true
    }
}

internal object SocialWebhookRouter {
    fun route(
        config: SocialConnectorConfig,
        body: String,
        signature: String,
        deduplicator: SocialInboundDeduplicator,
        now: Long = System.currentTimeMillis(),
    ): Pair<SocialInboundDisposition, SocialRoutedMessage?> {
        if (!config.enabled) return SocialInboundDisposition.INVALID to null
        val message = runCatching {
            SocialConnectorPolicy.validate(config)
            require(SocialWebhook.verifySignature(config.inboundToken, body, signature)) { "签名无效" }
            SocialWebhook.parse(config.platform, body)
        }.getOrNull() ?: return SocialInboundDisposition.INVALID to null
        if (kotlin.math.abs(now - message.timestamp) > MAX_CLOCK_SKEW_MILLIS) {
            return SocialInboundDisposition.STALE to null
        }
        val identity = if (message.messageId.isBlank()) {
            "${message.platform}:${message.externalUserId}:${message.timestamp}:${message.text.hashCode()}"
        } else {
            "${message.platform}:${message.externalUserId}:${message.messageId}"
        }
        if (!deduplicator.accept(identity, now)) return SocialInboundDisposition.DUPLICATE to null
        return SocialInboundDisposition.ACCEPT to SocialRoutedMessage(
            message = message,
            allowedTools = SocialConnectorPolicy.allowedTools(config.platform),
        )
    }

    private const val MAX_CLOCK_SKEW_MILLIS = 10 * 60 * 1_000L
}
