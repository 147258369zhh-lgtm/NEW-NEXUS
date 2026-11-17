package io.github.mangi.eta.ui.screens.tools

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.WebToolsCredentials
import io.github.mangi.eta.data.model.WebToolsSettings
import io.github.mangi.eta.data.repository.WebToolsCredentialsUnavailable
import io.github.mangi.eta.data.repository.WebToolsSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal typealias WebToolsValidationFailure = io.github.mangi.eta.data.repository.WebToolsValidationFailure

internal class WebToolsSettingsStore(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private var savedSettings by mutableStateOf<WebToolsSettings?>(null)
    private var savedCredentials by mutableStateOf<WebToolsCredentials?>(null)

    var settings by mutableStateOf(WebToolsSettings())
        private set
    var credentials by mutableStateOf(WebToolsCredentials())
        private set
    var hasProviderDeepSeekKey by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    private var credentialsReadable by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
        private set
    var messageIsError by mutableStateOf(false)
        private set
    val hasUnsavedChanges: Boolean
        get() = loaded && (settings != savedSettings || credentials != savedCredentials)
    val editable: Boolean get() = loaded && credentialsReadable && !saving

    init {
        scope.launch {
            try {
                settings = WebToolsSettingsRepository.settings()
                savedSettings = settings
                hasProviderDeepSeekKey = WebToolsSettingsRepository.deepSeekProviderCredentials() != null
                try {
                    credentials = WebToolsSettingsRepository.credentials(context)
                    credentialsReadable = true
                } catch (_: WebToolsCredentialsUnavailable) {
                    message = context.getString(R.string.web_tools_credentials_lost)
                    messageIsError = true
                }
                savedCredentials = credentials
                loaded = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = context.getString(R.string.web_tools_load_failed)
                messageIsError = true
            }
        }
    }

    fun edit(value: WebToolsSettings) {
        if (!editable) return
        settings = value
        message = null
    }

    fun editCredentials(value: WebToolsCredentials) {
        if (!editable) return
        credentials = value
        message = null
    }

    fun save() {
        if (!editable) return
        val config = settings
        val secrets = credentials
        saving = true
        scope.launch {
            try {
                val latestProviderDeepSeek = WebToolsSettingsRepository.deepSeekProviderCredentials() != null
                hasProviderDeepSeekKey = latestProviderDeepSeek
                validateWebToolsSettings(config, secrets, latestProviderDeepSeek)
                io.github.mangi.eta.data.repository.WebToolsSettingsService(context).save(config, secrets)
                savedSettings = config
                savedCredentials = secrets
                message = context.getString(
                    if (settings == config && credentials == secrets) {
                        R.string.speech_saved
                    } else {
                        R.string.speech_saved_with_pending
                    },
                )
                messageIsError = false
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val reason = (error as? WebToolsValidationFailure)?.userMessage
                    ?: context.getString(R.string.speech_save_retry)
                message = context.getString(R.string.speech_save_failed, reason)
                messageIsError = true
            } finally {
                saving = false
            }
        }
    }
}

internal fun validateWebToolsSettings(
    settings: WebToolsSettings,
    credentials: WebToolsCredentials,
    hasProviderDeepSeekKey: Boolean,
) {
    io.github.mangi.eta.data.repository.validateWebToolsConfiguration(settings, credentials, hasProviderDeepSeekKey)
}
