package io.github.mangi.eta.agent.social

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class SocialPlatform { QQ_BOT, TELEGRAM, DISCORD, WECHAT }

internal data class SocialConnectorConfig(
    val platform: SocialPlatform,
    val endpoint: String,
    val enabled: Boolean = false,
    val inboundToken: String = "",
)

internal object SocialConnectorPolicy {
    fun validate(config: SocialConnectorConfig): HttpUrl {
        require(config.endpoint.toHttpUrlOrNull()?.scheme == "https") { "社交入口必须使用 HTTPS" }
        require(config.inboundToken.length >= 16) { "入站 Token 至少 16 个字符" }
        return requireNotNull(config.endpoint.toHttpUrlOrNull())
    }

    fun allowedTools(platform: SocialPlatform): Set<String> = when (platform) {
        SocialPlatform.QQ_BOT, SocialPlatform.TELEGRAM, SocialPlatform.DISCORD, SocialPlatform.WECHAT ->
            setOf("get_current_context", "memory_get", "memory_write", "schedule_list")
    }
}
