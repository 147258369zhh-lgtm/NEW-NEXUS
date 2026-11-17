package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.control.NexusSettingsControlPlane
import io.github.mangi.eta.agent.control.WebToolsSettingsControlProvider
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.WebToolsCredentials
import io.github.mangi.eta.data.model.WebToolsSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class WebToolsSettingsService(context: Context) {
    private val context = context.applicationContext
    suspend fun save(config: WebToolsSettings, credentials: WebToolsCredentials) {
        validateWebToolsConfiguration(config, credentials, WebToolsSettingsRepository.deepSeekProviderCredentials() != null)
        val before = WebToolsSettingsRepository.settings()
        // Never replace unreadable encrypted credentials with an empty draft.
        val oldSecrets = WebToolsSettingsRepository.credentials(context)
        val privateBefore = WebToolsSettingsControlProvider.privateHash(context, before)
        val privateChange = oldSecrets != credentials || config.customSearch.headersText != before.customSearch.headersText ||
            config.customFetch.headersText != before.customFetch.headersText || config.customSearch.bodyTemplate != before.customSearch.bodyTemplate ||
            config.customFetch.bodyTemplate != before.customFetch.bodyTemplate || config.customSearch.url != before.customSearch.url ||
            config.customFetch.url != before.customFetch.url || config.deepSeek.baseUrl != before.deepSeek.baseUrl
        val parameters = JSONObject().put("value", WebToolsSettingsControlProvider.publicValue(config)).put("privateChange", privateChange)
        NexusSettingsControlPlane.get(context).mutateOwnedSettingsFromUi("settings.web_tools", parameters, "web_tools_settings") {
            check(WebToolsSettingsRepository.settings() == before && WebToolsSettingsControlProvider.privateHash(context, before) == privateBefore) {
                "网页工具配置已变化，请重新打开"
            }
            val secretPrefs = context.getSharedPreferences("eta_web_tools_secrets", Context.MODE_PRIVATE)
            val encryptedBefore = secretPrefs.getString("credentials", null)
            withContext(NonCancellable) {
                try {
                    if (oldSecrets != credentials) WebToolsSettingsRepository.saveCredentials(context, credentials)
                    SettingsDataStore.setWebToolsSettings(config)
                } catch (failure: Exception) {
                    if (WebToolsSettingsRepository.settings() == before && !secretPrefs.edit().putString("credentials", encryptedBefore).commit())
                        throw IllegalStateException("网页设置保存结果未确认，请核对配置后重试", failure)
                    throw failure
                }
            }
            WebToolsSettingsControlProvider.snapshot(context, privateChange)
        }
    }
}
