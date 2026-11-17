package io.github.mangi.eta.ui.screens.tools

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.CloudCapabilities
import io.github.mangi.eta.data.model.CloudCapabilityRoute
import io.github.mangi.eta.data.model.CloudToolsCredentials
import io.github.mangi.eta.data.model.CloudToolsSettings
import io.github.mangi.eta.data.repository.CloudToolsSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class CloudToolsSettingsStore(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private var savedSettings by mutableStateOf<CloudToolsSettings?>(null)
    private var savedCredentials by mutableStateOf<CloudToolsCredentials?>(null)

    var settings by mutableStateOf(CloudToolsSettings())
        private set
    var credentials by mutableStateOf(CloudToolsCredentials())
        private set
    var loaded by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var credentialsReadable by mutableStateOf(false)
        private set
    var credentialsError by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var messageIsError by mutableStateOf(false)
        private set
    val hasUnsavedChanges: Boolean
        get() = loaded && (settings != savedSettings ||
            (credentialsReadable && credentials != savedCredentials))
    val editable: Boolean get() = loaded && !loading && !saving

    init { reload() }

    fun reload() {
        if (loading || saving || loaded) return
        loading = true
        message = null
        scope.launch {
            try {
                val value = CloudToolsSettingsRepository.settings()
                settings = value
                savedSettings = value
                readCredentials()
                loaded = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = context.getString(R.string.cloud_tools_load_failed)
                messageIsError = true
            } finally {
                loading = false
            }
        }
    }

    fun retryCredentials() {
        if (!editable || credentialsReadable) return
        loading = true
        scope.launch {
            try { readCredentials() } finally { loading = false }
        }
    }

    private suspend fun readCredentials() {
        try {
            val value = CloudToolsSettingsRepository.credentials(context)
            credentials = value
            savedCredentials = value
            credentialsReadable = true
            credentialsError = null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            // Keep the stored ciphertext untouched; a blank fallback must never be saved over it.
            credentialsReadable = false
            credentialsError = context.getString(R.string.cloud_tools_credentials_unreadable)
        }
    }

    fun edit(value: CloudToolsSettings) {
        if (!editable) return
        settings = value
        message = null
    }

    fun editRoute(operation: String, value: CloudCapabilityRoute) {
        edit(settings.copy(routes = settings.routes + (operation to value)))
    }

    fun editCredentials(value: CloudToolsCredentials) {
        if (!editable || !credentialsReadable) return
        credentials = value
        message = null
    }

    fun credentialChanged(provider: String): Boolean = savedCredentials?.let {
        providerCredential(credentials, provider) != providerCredential(it, provider)
    } ?: false

    fun save() {
        if (!editable || !hasUnsavedChanges) return
        val config = settings
        val secrets = credentials
        val settingsChanged = config != savedSettings
        val credentialsChanged = credentialsReadable && secrets != savedCredentials
        saving = true
        message = null
        scope.launch {
            var credentialsWritten = false
            try {
                validateCloudSettings(config)
                if (credentialsChanged) {
                    validateSecrets(secrets)
                    val current = CloudToolsSettingsRepository.credentials(context)
                    check(current == savedCredentials) {
                        context.getString(R.string.cloud_tools_credentials_changed_elsewhere)
                    }
                    CloudToolsSettingsRepository.saveCredentials(context, secrets)
                    savedCredentials = secrets
                    credentialsWritten = true
                }
                if (settingsChanged) {
                    CloudToolsSettingsRepository.save(config)
                    savedSettings = config
                }
                message = context.getString(R.string.cloud_tools_saved)
                messageIsError = false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val validation = (error as? CloudSettingsValidationFailure)?.message
                message = when {
                    validation != null -> validation
                    credentialsWritten -> context.getString(R.string.cloud_tools_settings_save_partial)
                    error.message == context.getString(R.string.cloud_tools_credentials_changed_elsewhere) -> error.message
                    else -> context.getString(R.string.cloud_tools_save_failed)
                }
                messageIsError = true
            } finally {
                saving = false
            }
        }
    }

    private fun validateCloudSettings(value: CloudToolsSettings) {
        fun invalid(resource: Int): Nothing = throw CloudSettingsValidationFailure(context.getString(resource))
        fun endpoint(raw: String, optional: Boolean = false, rootOnly: Boolean = false) {
            if (raw.isBlank() && optional) return
            val url = raw.toHttpUrlOrNull() ?: invalid(R.string.cloud_tools_invalid_endpoint)
            if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.query != null || url.fragment != null || (rootOnly && url.encodedPath != "/")) {
                invalid(R.string.cloud_tools_invalid_endpoint)
            }
        }
        endpoint(value.deepSeek.baseUrl)
        endpoint(value.bailian.baseUrl, optional = true)
        endpoint(value.baidu.baseUrl, rootOnly = true)
        endpoint(value.tencent.baseUrl, rootOnly = true)
        val workspace = value.bailian.workspaceId
        if (workspace.isNotEmpty() && !workspace.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,127}"))) {
            invalid(R.string.cloud_tools_invalid_workspace)
        }
        if (!value.bailian.region.matches(Regex("[a-z][a-z0-9-]{1,40}")) ||
            !value.tencent.region.matches(Regex("[a-z][a-z0-9-]{1,40}"))) {
            invalid(R.string.cloud_tools_invalid_region)
        }
        val models = with(value.bailian) { listOf(imageModel, imageEditModel, videoUnderstandingModel,
            videoGenerationModel, imageToVideoModel, embeddingModel, rerankModel, translationModel) } + value.deepSeek.model
        if (models.any { !it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) }) {
            invalid(R.string.cloud_tools_invalid_model)
        }
        if (value.tencent.bizType.length > 128) invalid(R.string.cloud_tools_invalid_biz_type)
        if (value.routes.values.any { it.timeoutSeconds !in 1..600 || it.maxFileMiB !in 1..100 }) {
            invalid(R.string.cloud_tools_invalid_limits)
        }
        for (definition in CloudCapabilities.definitions) {
            val route = value.routes[definition.operation] ?: continue
            if (route.primary !in definition.providers ||
                (route.backup.isNotEmpty() && route.backup !in definition.providers)) {
                invalid(R.string.cloud_tools_invalid_route)
            }
        }
    }

    private fun validateSecrets(value: CloudToolsCredentials) {
        if (listOf(value.deepSeekApiKey, value.bailianApiKey, value.baiduApiKey, value.baiduSecretKey,
                value.tencentSecretId, value.tencentSecretKey).any { secret ->
                secret.length > 8192 || secret.any { it == '\r' || it == '\n' }
            }) throw CloudSettingsValidationFailure(context.getString(R.string.cloud_tools_invalid_credentials))
    }
}

private class CloudSettingsValidationFailure(message: String) : IllegalArgumentException(message)

internal fun providerCredential(value: CloudToolsCredentials, provider: String): List<String> = when (provider) {
    "deepseek" -> listOf(value.deepSeekApiKey)
    "bailian" -> listOf(value.bailianApiKey)
    "baidu" -> listOf(value.baiduApiKey, value.baiduSecretKey)
    "tencent" -> listOf(value.tencentSecretId, value.tencentSecretKey)
    else -> emptyList()
}
