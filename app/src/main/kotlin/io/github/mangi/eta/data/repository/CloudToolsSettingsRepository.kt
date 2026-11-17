package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.CloudToolsCredentials
import io.github.mangi.eta.data.model.CloudToolsRuntimeConfig
import io.github.mangi.eta.data.model.CloudToolsSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Non-secret settings follow normal app storage; only an explicit cloud credential profile is used. */
internal object CloudToolsSettingsRepository {
    private const val CREDENTIAL_PROFILE = "nexa-cloud-tools-v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun settingsFlow(): Flow<CloudToolsSettings> = SettingsDataStore.cloudToolsSettingsFlow()

    suspend fun settings(): CloudToolsSettings = SettingsDataStore.cloudToolsSettings()

    suspend fun save(settings: CloudToolsSettings) {
        require(settings.routes.values.all { it.timeoutSeconds in 1..600 && it.maxFileMiB in 1..100 }) {
            "云能力超时或文件限制无效"
        }
        SettingsDataStore.setCloudToolsSettings(settings)
    }

    /** Update only this owner; online model settings must not replace unrelated cloud routes. */
    suspend fun saveOnlineImage(context: Context, value: io.github.mangi.eta.data.model.OnlineImageGenerationConfig,
        apiKey: String?, expected: io.github.mangi.eta.data.model.OnlineImageGenerationConfig) {
        if (value.enabled || value.baseUrl.isNotBlank()) io.github.mangi.eta.agent.cloud.OnlineImageGeneration.endpoint(value)
        val current = settings()
        check(current.onlineImage == expected) { "图片模型设置已在其他位置修改，请返回后重新打开" }
        if (apiKey != null) {
            require(apiKey.length <= 8192 && apiKey.none { it == '\r' || it == '\n' }) { "图片 API Key 无效" }
            val existing = credentials(context)
            if (value.enabled) require(apiKey.isNotBlank()) { "启用前请保存图片 API Key" }
            saveCredentials(context, existing.copy(onlineImageApiKey = apiKey))
        } else if (value.enabled) require(credentials(context).onlineImageApiKey.isNotBlank()) { "启用前请保存图片 API Key" }
        save(current.copy(onlineImage = value))
    }

    suspend fun credentials(context: Context): CloudToolsCredentials = withContext(Dispatchers.IO) {
        val raw = McpSecretStore(context).bearerToken(CREDENTIAL_PROFILE)
            ?: return@withContext CloudToolsCredentials()
        try {
            json.decodeFromString<CloudToolsCredentials>(raw)
        } catch (_: Exception) {
            throw McpCredentialsUnavailable("云能力凭据无法解密或读取，请重新保存凭据")
        }
    }

    suspend fun saveCredentials(context: Context, credentials: CloudToolsCredentials) = withContext(Dispatchers.IO) {
        McpSecretStore(context).setBearerToken(CREDENTIAL_PROFILE, json.encodeToString(credentials))
    }

    /** Do not downgrade unreadable credentials to an anonymous call or silently borrow a chat key. */
    suspend fun resolveRuntimeConfig(context: Context): CloudToolsRuntimeConfig =
        CloudToolsRuntimeConfig(settings(), credentials(context), ProviderRepository.allProviders())
}
