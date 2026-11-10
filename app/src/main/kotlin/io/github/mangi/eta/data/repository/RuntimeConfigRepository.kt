package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelFallbackPolicy
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelFallbackSettings
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.provider.ReasoningCapabilityResolver
import io.github.libxposed.service.XposedService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class SubagentConfigSource(val wireValue: String) {
    EXPLICIT("explicit"),
    PROFILE("profile"),
    GLOBAL("global"),
    PRIMARY("primary"),
}

internal data class ResolvedSubagentConfig(
    val config: AgentModelClient.ModelConfig,
    val modelSource: SubagentConfigSource,
    val reasoningSource: SubagentConfigSource,
    val fallbackReason: String? = null,
    val toolsOverride: List<String>? = null,
    val disallowedTools: Set<String> = emptySet(),
    val maxToolCalls: Int? = null,
)

internal object RuntimeConfigRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun selectedProviderIdFlow() = SettingsDataStore.selectedProviderIdFlow()

    fun selectedModelIdFlow() = SettingsDataStore.selectedModelIdFlow()

    fun subagentSettingsFlow() = SettingsDataStore.subagentSettingsFlow()

    suspend fun setSubagentSettings(settings: SubagentSettings) {
        SettingsDataStore.setSubagentSettings(settings)
    }

    fun modelFallbackSettingsFlow() = SettingsDataStore.modelFallbackSettingsFlow()

    suspend fun modelFallbackSettings(): ModelFallbackSettings = SettingsDataStore.modelFallbackSettings()

    suspend fun setModelFallbackSettings(settings: ModelFallbackSettings) {
        SettingsDataStore.setModelFallbackSettings(settings)
    }

    /** Resolves the explicitly selected backup model and removes privileged tools by default. */
    suspend fun fallbackRuntimeConfig(primary: AgentModelClient.ModelConfig): AgentModelClient.ModelConfig? {
        val settings = SettingsDataStore.modelFallbackSettings()
        if (!settings.enabled || settings.modelId.isNullOrBlank()) return null
        ProviderRepository.ensureBuiltInsMerged()
        val selected = ProviderRepository.allProviders().enabledModel(
            settings.modelId,
            settings.providerId,
        ) ?: return null
        val fallback = buildRuntimeConfig(selected.provider, selected.model)
        if (fallback.providerId == primary.providerId && fallback.baseUrl == primary.baseUrl && fallback.model == primary.model) return null
        return AgentModelFallbackPolicy.constrainConfig(primary, fallback)
    }

    /**
     * 解析 Subagent 实际运行所用的模型配置。
     *
     * 指定了独立子模型且该模型仍处于启用状态时优先使用子模型，否则平滑回退到主模型；
     * 工具开关继承父请求，并强制关闭 `subagentTools` 防止递归派发。
     */
    suspend fun subagentRuntimeConfig(
        primaryConfig: AgentModelClient.ModelConfig,
        profileName: String,
        explicitProviderId: String? = null,
        explicitModelId: String? = null,
        explicitReasoningEffort: ReasoningEffort? = null,
    ): ResolvedSubagentConfig {
        ProviderRepository.ensureBuiltInsMerged()
        val providers = ProviderRepository.allProviders()
        return resolveSubagentRuntimeConfig(
            primaryConfig = primaryConfig,
            settings = SettingsDataStore.subagentSettings(),
            providers = providers,
            profileName = profileName,
            explicitProviderId = explicitProviderId,
            explicitModelId = explicitModelId,
            explicitReasoningEffort = explicitReasoningEffort,
        )
    }

    internal fun resolveSubagentRuntimeConfig(
        primaryConfig: AgentModelClient.ModelConfig,
        settings: SubagentSettings,
        providers: List<ProviderSetting>,
        profileName: String,
        explicitProviderId: String? = null,
        explicitModelId: String? = null,
        explicitReasoningEffort: ReasoningEffort? = null,
    ): ResolvedSubagentConfig {
        val normalizedProfile = profileName.trim().lowercase()
        val profileBinding = settings.profiles[normalizedProfile]
        val fallbackReasons = mutableListOf<String>()
        val attempts = buildList {
            if (!explicitProviderId.isNullOrBlank() || !explicitModelId.isNullOrBlank()) {
                add(
                    ModelAttempt(
                        source = SubagentConfigSource.EXPLICIT,
                        providerId = explicitProviderId?.trim()?.takeIf(String::isNotEmpty),
                        modelId = explicitModelId?.trim()?.takeIf(String::isNotEmpty),
                    ),
                )
            }
            if (!profileBinding?.providerId.isNullOrBlank() || !profileBinding?.modelId.isNullOrBlank()) {
                add(
                    ModelAttempt(
                        source = SubagentConfigSource.PROFILE,
                        providerId = profileBinding?.providerId?.trim()?.takeIf(String::isNotEmpty),
                        modelId = profileBinding?.modelId?.trim()?.takeIf(String::isNotEmpty),
                    ),
                )
            }
            if (!settings.modelId.isNullOrBlank()) {
                add(
                    ModelAttempt(
                        source = SubagentConfigSource.GLOBAL,
                        modelId = settings.modelId?.trim()?.takeIf(String::isNotEmpty),
                    ),
                )
            }
        }

        var selected: SelectedModel? = null
        for (attempt in attempts) {
            selected = findEnabledModel(providers, attempt)
            if (selected != null) break
            fallbackReasons += attempt.unavailableReason()
        }
        val modelSource = selected?.attempt?.source ?: SubagentConfigSource.PRIMARY
        val base = selected?.let { buildRuntimeConfig(it.provider, it.model) } ?: primaryConfig

        val reasoningAttempts = buildList {
            explicitReasoningEffort?.let { add(SubagentConfigSource.EXPLICIT to it) }
            val profileReasoning = profileBinding?.reasoningEffort
            if (!profileReasoning.isNullOrBlank()) {
                val resolved = ReasoningEffort.fromWireValue(profileReasoning)
                if (resolved == null) {
                    fallbackReasons += "Profile $normalizedProfile 的思考强度 $profileReasoning 无法识别"
                } else {
                    add(SubagentConfigSource.PROFILE to resolved)
                }
            }
            val globalReasoning = settings.reasoningEffort
            if (!globalReasoning.isNullOrBlank()) {
                val resolved = ReasoningEffort.fromWireValue(globalReasoning)
                if (resolved == null) {
                    fallbackReasons += "全局思考强度 $globalReasoning 无法识别"
                } else {
                    add(SubagentConfigSource.GLOBAL to resolved)
                }
            }
        }
        val reasoningSelection = reasoningAttempts.firstOrNull()
        val requestedEffort = reasoningSelection?.second
        val normalizedEffort = normalizeReasoningEffort(base, requestedEffort)
        if (requestedEffort != null && normalizedEffort != requestedEffort) {
            fallbackReasons += "思考强度 ${requestedEffort.wireValue} 不受当前模型支持，已调整为 ${normalizedEffort.wireValue}"
        }
        val reasoningSource = when {
            reasoningSelection != null -> reasoningSelection.first
            else -> SubagentConfigSource.PRIMARY
        }
        val contextWindowOverride = profileBinding?.contextWindow?.let { requested ->
            if (requested > 0) {
                requested
            } else {
                fallbackReasons += "Profile $normalizedProfile 的上下文预算必须大于 0"
                null
            }
        }
        // Catalog windows are metadata until the user configures a model. Subagent negotiation
        // still needs that metadata as a hard ceiling so a Profile cannot claim a larger window.
        val baseContextWindow = base.knownContextWindow ?: selected?.model?.contextWindow
        val negotiatedContextWindow = when {
            contextWindowOverride == null -> base.contextWindow
            baseContextWindow == null -> contextWindowOverride
            else -> minOf(baseContextWindow, contextWindowOverride)
        }
        val maxToolCalls = profileBinding?.maxToolCalls?.let { requested ->
            if (requested > 0) {
                requested
            } else {
                fallbackReasons += "Profile $normalizedProfile 的工具调用预算必须大于 0"
                null
            }
        }
        val config = base.copy(
            contextWindow = negotiatedContextWindow,
            terminalTools = primaryConfig.terminalTools,
            browserTools = primaryConfig.browserTools,
            deviceDirectTools = primaryConfig.deviceDirectTools,
            deviceSensitiveReadTools = primaryConfig.deviceSensitiveReadTools,
            deviceSensitiveActionTools = primaryConfig.deviceSensitiveActionTools,
            subagentTools = false,
            thinkingEnabled = normalizedEffort.enablesReasoning,
            reasoningEffort = normalizedEffort,
            autoCompactionEnabled = primaryConfig.autoCompactionEnabled,
            supportsSearchSuggestions = false,
        )
        return ResolvedSubagentConfig(
            config = config,
            modelSource = modelSource,
            reasoningSource = reasoningSource,
            fallbackReason = fallbackReasons
                .distinct()
                .takeIf(List<String>::isNotEmpty)
                ?.joinToString("；")
                ?.take(1_000),
            toolsOverride = profileBinding?.tools
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.distinct(),
            disallowedTools = profileBinding?.disallowedTools
                .orEmpty()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet(),
            maxToolCalls = maxToolCalls,
        )
    }

    private data class ModelAttempt(
        val source: SubagentConfigSource,
        val providerId: String? = null,
        val modelId: String? = null,
    ) {
        fun unavailableReason(): String {
            val target = when {
                providerId != null && modelId != null -> "provider=$providerId, model=$modelId"
                providerId != null -> "provider=$providerId"
                else -> "model=$modelId"
            }
            val label = when (source) {
                SubagentConfigSource.EXPLICIT -> "显式指定"
                SubagentConfigSource.PROFILE -> "Profile 绑定"
                SubagentConfigSource.GLOBAL -> "全局默认"
                SubagentConfigSource.PRIMARY -> "主会话"
            }
            return "$label 的模型不可用（$target）"
        }
    }

    private data class SelectedModel(
        val attempt: ModelAttempt,
        val provider: ProviderSetting,
        val model: Model,
    )

    private fun findEnabledModel(
        providers: List<ProviderSetting>,
        attempt: ModelAttempt,
    ): SelectedModel? {
        val providerId = attempt.providerId
        val modelId = attempt.modelId
        val enabledProviders = providers.filter(ProviderSetting::isEnabled)
        if (modelId != null) {
            for (provider in enabledProviders) {
                if (providerId != null && provider.id != providerId) continue
                val model = provider.models.firstOrNull {
                    it.isEnabled && (it.id == modelId || it.modelId == modelId)
                } ?: continue
                return SelectedModel(
                    attempt = attempt,
                    provider = provider,
                    model = model,
                )
            }
            return null
        }
        if (providerId == null) return null
        val provider = enabledProviders.firstOrNull { it.id == providerId } ?: return null
        val model = provider.selectedOrFirstModel(null) ?: return null
        return SelectedModel(
            attempt = attempt,
            provider = provider,
            model = model,
        )
    }

    private fun normalizeReasoningEffort(
        config: AgentModelClient.ModelConfig,
        requested: ReasoningEffort?,
    ): ReasoningEffort {
        if (requested == null) return config.effectiveReasoningEffort
        val capabilities = config.reasoningCapabilities
        return when {
            capabilities != null -> capabilities.normalize(requested)
            requested == ReasoningEffort.OFF -> ReasoningEffort.OFF
            else -> config.effectiveReasoningEffort
        }
    }

    suspend fun selectedProvider(): ProviderSetting? {
        val settings = ProviderRepository.repairSelection()
        return settings.selectedProviderId?.let { ProviderRepository.providerById(it) }
    }

    suspend fun setSelectedProviderId(id: String?) {
        val settings = SettingsDataStore.settings()
        val provider = id?.let { ProviderRepository.providerById(it) }
            ?.takeIf { it.isEnabled }
        val activeModel = provider
            ?.takeIf { it.id == settings.selectedProviderId }
            ?.models
            ?.firstOrNull { it.id == settings.selectedModelId && it.isEnabled }
        val rememberedModelId = provider?.let {
            SettingsDataStore.selectedModelIdForProvider(it.id)
        }
        val model = activeModel ?: provider?.selectedOrFirstModel(rememberedModelId)
        SettingsDataStore.setSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun setSelectedModelId(id: String?) {
        val provider = id?.let { ProviderRepository.providerByModelId(it) }
            ?.takeIf { it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == id && it.isEnabled }
        SettingsDataStore.setSelection(
            providerId = provider?.id,
            modelId = model?.id,
        )
        ProviderRepository.repairSelection()
    }

    suspend fun currentRuntimeConfig(): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        val settings = ProviderRepository.repairSelection()
        val provider = settings.selectedProviderId?.let { ProviderRepository.providerById(it) } ?: return null
        val model = provider.selectedOrFirstModel(settings.selectedModelId) ?: return null
        return buildRuntimeConfig(provider, model)
    }

    /** 会话绑定的模型仍可用时使用它，否则回落到默认模型。 */
    suspend fun runtimeConfigFor(modelId: String?): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.allProviders().enabledModel(modelId)?.let { (provider, model) ->
            return buildRuntimeConfig(provider, model)
        }
        return currentRuntimeConfig()
    }

    suspend fun oauthRuntimeConfigFor(
        providerId: String,
        authMode: String,
        modelName: String,
    ): AgentModelClient.ModelConfig? {
        if (!ProviderAuthModes.isOAuth(authMode)) return null
        val provider = ProviderRepository.providerById(providerId)
            ?.takeIf { it.isEnabled && it.authMode == authMode } ?: return null
        val model = provider.models.firstOrNull { it.isEnabled && it.modelId == modelName } ?: return null
        return buildRuntimeConfig(provider, model)
    }

    suspend fun syncToRemotePreferences(service: XposedService?): Boolean {
        val prefs = Prefs.remotePreferencesForUi(service) ?: return false
        val config = currentRuntimeConfig() ?: return clearRuntimeConfig(prefs)
        return writeRuntimeConfig(prefs, config)
    }

    suspend fun ensureDefaults(service: XposedService?) {
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.repairSelection()
        syncToRemotePreferences(service)
    }

    fun runtimeConfigJson(config: AgentModelClient.ModelConfig): String =
        json.encodeToString(if (config.authMode == ProviderAuthModes.API_KEY) config else config.copy(apiKey = ""))

    fun buildRuntimeConfig(provider: ProviderSetting, model: Model): AgentModelClient.ModelConfig {
        val systemPrompt = provider.systemPrompt.orEmpty().trim()
        val sourceType = ProviderSourceRegistry.resolve(provider)
        val profile = ProviderConnectionProfile.forAuthMode(provider.authMode)
        val endpointMode = profile?.endpointMode ?: provider.endpointMode()
        val reasoningCapabilities = reasoningCapabilities(provider, model)
        return AgentModelClient.ModelConfig(
            providerId = provider.id,
            providerName = provider.name,
            providerType = profile?.providerType ?: provider.runtimeProviderType,
            providerSourceType = sourceType,
            baseUrl = profile?.baseUrl ?: provider.baseUrl.trim(),
            apiKey = provider.apiKey.trim().takeIf { provider.authMode == ProviderAuthModes.API_KEY }.orEmpty(),
            authMode = provider.authMode,
            model = model.modelId.trim(),
            modelDisplayName = model.displayName.trim(),
            contextWindow = model.effectiveContextWindow,
            autoCompactionEnabled = Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPACTION_ENABLED),
            subagentTools = Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED),
            systemPrompt = systemPrompt,
            anthropicVersion = (provider as? AnthropicProviderSetting)?.anthropicVersion
                ?: AnthropicProviderSetting.DEFAULT_ANTHROPIC_VERSION,
            openAiEndpointMode = endpointMode,
            hostedWebSearchEnabled = provider.hostedWebSearchEnabled,
            supportsTools = model.toolCall != false,
            thinkingEnabled = reasoningCapabilities != null,
            reasoningEffort = reasoningCapabilities?.let { ReasoningEffort.DEFAULT }
                ?: ReasoningEffort.OFF,
            reasoningCapabilities = reasoningCapabilities,
            customHeaders = provider.customHeaders + model.customHeaders,
            customBody = provider.customBody + model.customBody,
        )
    }

    fun reasoningCapabilities(provider: ProviderSetting, model: Model): ModelReasoningCapabilities? {
        if (provider is GeminiProviderSetting) {
            return GeminiModelCapabilities.requestReasoningCapabilities(model.modelId)
        }
        val sourceType = ProviderSourceRegistry.resolve(provider)
        val inferOpenAiCatalog = sourceType == io.github.mangi.eta.data.model.ProviderSourceTypes.CUSTOM &&
            provider.endpointMode() == OpenAiEndpointMode.RESPONSES
        return ReasoningCapabilityResolver.resolve(
            sourceType = if (inferOpenAiCatalog) {
                io.github.mangi.eta.data.model.ProviderSourceTypes.OPENAI
            } else {
                sourceType
            },
            model = model,
            inferExactCatalogModel = inferOpenAiCatalog,
        )
    }

    private fun ProviderSetting.endpointMode(): String = when (this) {
        is OpenAiCompatibleProviderSetting -> endpointMode
        is CustomProviderSetting -> endpointMode
        is AnthropicProviderSetting, is GeminiProviderSetting -> ""
    }

    private fun writeRuntimeConfig(
        prefs: SharedPreferences,
        config: AgentModelClient.ModelConfig,
    ): Boolean =
        runCatching {
            prefs.edit()
                .putString(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON, runtimeConfigJson(config))
                .commit()
        }.getOrDefault(false)

    private fun clearRuntimeConfig(prefs: SharedPreferences): Boolean =
        runCatching {
            prefs.edit()
                .remove(Prefs.Keys.AGENT_RUNTIME_CONFIG_JSON)
                .commit()
        }.getOrDefault(false)
}
