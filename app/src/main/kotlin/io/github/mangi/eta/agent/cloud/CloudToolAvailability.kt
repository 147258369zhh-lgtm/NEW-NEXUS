package io.github.mangi.eta.agent.cloud

import io.github.mangi.eta.agent.model.AgentCloudToolCatalog
import io.github.mangi.eta.data.model.CloudCapabilities
import io.github.mangi.eta.data.model.CloudToolsRuntimeConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Discovery and execution share the same explicit configuration and credential boundary. */
internal object CloudToolAvailability {
    fun isCloudTool(toolName: String): Boolean = toolName in AgentCloudToolCatalog.names

    fun isAvailable(toolName: String, config: CloudToolsRuntimeConfig): Boolean =
        isCloudTool(toolName) && CloudCapabilities.definitions.any { definition ->
            unavailableCode(toolName, definition.operation, config) == null
        }

    /** Null permits this operation; never borrow model credentials or select an anonymous API. */
    fun unavailableCode(toolName: String, operation: String, config: CloudToolsRuntimeConfig): String? {
        if (toolName == AgentCloudToolCatalog.MEDIA && operation == "image_generate" &&
            OnlineImageGeneration.candidates(config.imageProviders).isNotEmpty()) {
            if (config.settings.routes[operation]?.enabled == false) return "cloud_operation_disabled"
            val route = config.settings.routes[operation]
            if (route != null && (route.timeoutSeconds !in 1..600 || route.maxFileMiB !in 1..100)) return "cloud_configuration_invalid"
            return if (OnlineImageGeneration.candidates(config.imageProviders).any(OnlineImageGeneration::candidateValid)) null
                else "cloud_configuration_invalid"
        }
        if (toolName == AgentCloudToolCatalog.MEDIA && operation == "image_generate" && config.settings.onlineImage.enabled) {
            if (config.settings.routes[operation]?.enabled == false) return "cloud_operation_disabled"
            val key = config.credentials.onlineImageApiKey
            if (key.isBlank()) return "cloud_credentials_missing"
            if (key.length > 8192 || key.any { it == '\r' || it == '\n' }) return "cloud_configuration_invalid"
            val route = config.settings.routes[operation]
            if (route != null && (route.timeoutSeconds !in 1..600 || route.maxFileMiB !in 1..100)) return "cloud_configuration_invalid"
            return if (runCatching { OnlineImageGeneration.endpoint(config.settings.onlineImage) }.isSuccess) null
                else "cloud_configuration_invalid"
        }
        if (!config.settings.enabled) return "cloud_tools_disabled"
        val media = operation in CloudMediaTools.operations
        val expectedTool = if (media) AgentCloudToolCatalog.MEDIA else AgentCloudToolCatalog.DOCUMENT
        if (toolName != expectedTool || CloudCapabilities.definitions.none { it.operation == operation }) {
            return "unknown_cloud_operation"
        }
        if (config.settings.routes[operation]?.enabled == false) return "cloud_operation_disabled"
        // route() supplies defaults for older saved configurations and validates the provider set.
        val route = try { config.route(operation) }
        catch (_: IllegalArgumentException) { return "cloud_configuration_invalid" }
        catch (_: IllegalStateException) { return "cloud_configuration_invalid" }
        val provider = when {
            config.credentials.configured(route.primary) -> route.primary
            route.backup.isNotBlank() && config.credentials.configured(route.backup) -> route.backup
            else -> return "cloud_credentials_missing"
        }
        val endpoint = try {
            when (provider) {
                "deepseek" -> config.deepSeekBaseUrl()
                "bailian" -> config.bailianBaseUrl()
                "baidu" -> config.settings.baidu.baseUrl.trim()
                "tencent" -> config.settings.tencent.baseUrl.trim()
                else -> return "cloud_configuration_invalid"
            }.toHttpUrlOrNull()
        } catch (_: IllegalArgumentException) { return "cloud_configuration_invalid" }
        if (endpoint == null || !endpoint.isHttps || endpoint.username.isNotEmpty() || endpoint.password.isNotEmpty() ||
            endpoint.query != null || endpoint.fragment != null || provider == "tencent" && endpoint.encodedPath != "/") {
            return "cloud_configuration_invalid"
        }
        return null
    }
}
