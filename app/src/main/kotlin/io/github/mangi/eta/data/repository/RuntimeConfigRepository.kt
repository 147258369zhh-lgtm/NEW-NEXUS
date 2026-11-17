package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelFallbackPolicy
import io.github.mangi.eta.agent.model.ModelCandidateRouteDecision
import io.github.mangi.eta.agent.model.ModelCandidateRouteOption
import io.github.mangi.eta.agent.model.ModelCandidateRouteRequest
import io.github.mangi.eta.agent.model.ModelCandidateSelector
import io.github.mangi.eta.agent.model.ModelCandidateSource
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.AnthropicProviderSetting
import io.github.mangi.eta.data.model.AutomationModelSettings
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelFallbackSettings
import io.github.mangi.eta.data.model.ModelCandidatePurpose
import io.github.mangi.eta.data.model.ModelCandidateSettings
import io.github.mangi.eta.data.model.ModelRoleSettings
import io.github.mangi.eta.data.model.ModelRoleBinding
import io.github.mangi.eta.data.model.ModelRolePurpose
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderModel
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.SubagentProfileBinding
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.model.hasUsableConnection
import io.github.mangi.eta.data.model.runtimeProviderType
import io.github.mangi.eta.data.model.selectedOrFirstModel
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.provider.ProviderConnectionProfile
import io.github.mangi.eta.data.provider.ReasoningCapabilityResolver
import io.github.libxposed.service.XposedService
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class SubagentConfigSource(val wireValue: String) {
    EXPLICIT("explicit"),
    PROFILE("profile"),
    GLOBAL("global"),
    CANDIDATE("candidate"),
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

internal enum class ModelRoleSource { COMPACTION, AUXILIARY }

internal data class ResolvedModelRole(
    val provider: ProviderSetting,
    val model: Model,
    val source: ModelRoleSource,
)

internal object RuntimeConfigRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val defaultsMutex = Mutex()
    private var localDefaultsReady = false
    private var defaultsServiceSynced = false
    private var lastDefaultsService: XposedService? = null

    fun selectedProviderIdFlow() = SettingsDataStore.selectedProviderIdFlow()

    fun selectedModelIdFlow() = SettingsDataStore.selectedModelIdFlow()

    fun subagentSettingsFlow() = SettingsDataStore.subagentSettingsFlow()

    suspend fun subagentSettings() = SettingsDataStore.subagentSettings()

    suspend fun setSubagentSettings(settings: SubagentSettings) {
        io.github.mangi.eta.agent.control.NexusSettingsControlPlane.get(SettingsDataStore.applicationContext()).applyJson(
            "settings.subagents", org.json.JSONObject().put("value", org.json.JSONObject(json.encodeToString(settings))),
            io.github.zhh_lgtm.nexus.application.api.SettingsActorKind.LOCAL_USER)
    }

    fun modelFallbackSettingsFlow() = SettingsDataStore.modelFallbackSettingsFlow()

    suspend fun modelFallbackSettings(): ModelFallbackSettings = SettingsDataStore.modelFallbackSettings()

    suspend fun setModelFallbackSettings(settings: ModelFallbackSettings) {
        io.github.mangi.eta.agent.control.NexusSettingsControlPlane.get(SettingsDataStore.applicationContext()).applyJson(
            "settings.model_fallback", org.json.JSONObject().put("enabled", settings.enabled)
                .put("modelId", settings.modelId ?: org.json.JSONObject.NULL).put("providerId", settings.providerId ?: org.json.JSONObject.NULL),
            io.github.zhh_lgtm.nexus.application.api.SettingsActorKind.LOCAL_USER)
    }

    fun modelCandidateSettingsFlow() = SettingsDataStore.modelCandidateSettingsFlow()

    suspend fun modelCandidateSettings(): ModelCandidateSettings = SettingsDataStore.modelCandidateSettings()

    suspend fun setModelCandidateSettings(settings: ModelCandidateSettings) {
        val normalized = settings.normalized()
        val candidates = org.json.JSONArray().also { array ->
            normalized.candidates.forEach { candidate ->
                array.put(org.json.JSONObject()
                    .put("providerId", candidate.providerId)
                    .put("modelId", candidate.modelId)
                    .put("enabled", candidate.enabled)
                    .put("purpose", candidate.purpose.name)
                    .put("requiredTools", candidate.requiredTools)
                    .put("requiredVision", candidate.requiredVision)
                    .put("minimumContextWindow", candidate.minimumContextWindow ?: org.json.JSONObject.NULL)
                    .put("priority", candidate.priority))
            }
        }
        io.github.mangi.eta.agent.control.NexusSettingsControlPlane.get(SettingsDataStore.applicationContext()).applyJson(
            "settings.model_candidates",
            org.json.JSONObject().put("enabled", normalized.enabled).put("candidates", candidates),
            io.github.zhh_lgtm.nexus.application.api.SettingsActorKind.LOCAL_USER,
        )
    }

    fun automationModelSettingsFlow() = SettingsDataStore.automationModelSettingsFlow()

    suspend fun automationModelSettings(): AutomationModelSettings = SettingsDataStore.automationModelSettings()

    suspend fun setAutomationModelSettings(settings: AutomationModelSettings) {
        SettingsDataStore.setAutomationModelSettings(settings)
    }

    fun modelRoleSettingsFlow() = SettingsDataStore.modelRoleSettingsFlow()

    suspend fun modelRoleSettings(): ModelRoleSettings = SettingsDataStore.modelRoleSettings()

    suspend fun setModelRoleSettings(settings: ModelRoleSettings) {
        io.github.mangi.eta.agent.control.NexusSettingsControlPlane.get(SettingsDataStore.applicationContext()).applyJson(
            "settings.model_roles",
            org.json.JSONObject()
                .put("auxiliaryProviderId", settings.auxiliaryProviderId ?: org.json.JSONObject.NULL)
                .put("auxiliaryModelId", settings.auxiliaryModelId ?: org.json.JSONObject.NULL)
                .put("compactionProviderId", settings.compactionProviderId ?: org.json.JSONObject.NULL)
                .put("compactionModelId", settings.compactionModelId ?: org.json.JSONObject.NULL)
                .put("memoryProviderId", settings.memoryProviderId ?: org.json.JSONObject.NULL)
                .put("memoryModelId", settings.memoryModelId ?: org.json.JSONObject.NULL)
                .put("learningProviderId", settings.learningProviderId ?: org.json.JSONObject.NULL)
                .put("learningModelId", settings.learningModelId ?: org.json.JSONObject.NULL)
                .put("transcriptProviderId", settings.transcriptProviderId ?: org.json.JSONObject.NULL)
                .put("transcriptModelId", settings.transcriptModelId ?: org.json.JSONObject.NULL),
            io.github.zhh_lgtm.nexus.application.api.SettingsActorKind.LOCAL_USER,
        )
    }

    /** Resolves the configured compaction model; the active chat model is the final fallback. */
    suspend fun compactionRuntimeConfig(
        primary: AgentModelClient.ModelConfig,
        scopedBinding: ModelRoleBinding? = null,
    ): AgentModelClient.ModelConfig {
        ProviderRepository.ensureBuiltInsMerged()
        val providers = ProviderRepository.allProviders()
        val resolved = resolveCompactionRole(modelRoleSettings(), providers, scopedBinding)
            ?: return primary
        return buildRuntimeConfig(resolved.provider, resolved.model)
    }

    internal fun resolveCompactionRole(
        settings: ModelRoleSettings,
        providers: List<ProviderSetting>,
        scopedBinding: ModelRoleBinding? = null,
    ): ResolvedModelRole? {
        val normalized = settings.normalized()
        val selectable = providers.filter(ProviderSetting::hasUsableConnection)
        scopedBinding?.let { binding ->
            selectable.enabledModel(binding.modelId, binding.providerId)?.let { selected ->
                return ResolvedModelRole(selected.provider, selected.model, ModelRoleSource.COMPACTION)
            }
        }
        normalized.compactionModelId?.let { modelId ->
            selectable.enabledModel(modelId, normalized.compactionProviderId)?.let { selected ->
                return ResolvedModelRole(selected.provider, selected.model, ModelRoleSource.COMPACTION)
            }
        }
        normalized.auxiliaryModelId?.let { modelId ->
            selectable.enabledModel(modelId, normalized.auxiliaryProviderId)?.let { selected ->
                return ResolvedModelRole(selected.provider, selected.model, ModelRoleSource.AUXILIARY)
            }
        }
        return null
    }

    /** Resolve a maintenance purpose without changing the active chat model. */
    suspend fun modelRoleRuntimeConfig(
        purpose: ModelRolePurpose,
        primary: AgentModelClient.ModelConfig,
    ): AgentModelClient.ModelConfig {
        ProviderRepository.ensureBuiltInsMerged()
        return resolveModelRoleRuntimeConfig(
            purpose = purpose,
            settings = modelRoleSettings(),
            providers = ProviderRepository.allProviders(),
            primary = primary,
        )
    }

    internal fun resolveModelRoleRuntimeConfig(
        purpose: ModelRolePurpose,
        settings: ModelRoleSettings,
        providers: List<ProviderSetting>,
        primary: AgentModelClient.ModelConfig,
    ): AgentModelClient.ModelConfig {
        val normalized = settings.normalized()
        val selectable = providers.filter(ProviderSetting::hasUsableConnection)
        val explicit = when (purpose) {
            ModelRolePurpose.MEMORY -> normalized.memoryModelId to normalized.memoryProviderId
            ModelRolePurpose.LEARNING -> normalized.learningModelId to normalized.learningProviderId
            ModelRolePurpose.TRANSCRIPT -> normalized.transcriptModelId to normalized.transcriptProviderId
        }
        explicit.first?.let { modelId ->
            selectable.enabledModel(modelId, explicit.second)?.let { return buildRuntimeConfig(it.provider, it.model) }
        }
        normalized.auxiliaryModelId?.let { modelId ->
            selectable.enabledModel(modelId, normalized.auxiliaryProviderId)?.let {
                return buildRuntimeConfig(it.provider, it.model)
            }
        }
        return primary
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
     * Route only inside the current model plus the user's candidate bindings and the existing
     * single fallback. The current chat model is kept first; no other provider model is discovered.
     */
    suspend fun routeChatRuntimeConfig(
        primary: AgentModelClient.ModelConfig,
        primaryModelId: String,
        requirements: ModelCandidateRouteRequest,
    ): ModelCandidateRouteDecision {
        val primaryOption = ModelCandidateRouteOption(
            providerId = primary.providerId,
            modelId = primaryModelId.ifBlank { primary.model },
            config = primary,
            source = ModelCandidateSource.PRIMARY,
            authorized = true,
        )
        val settings = SettingsDataStore.modelCandidateSettings()
        if (!settings.enabled) return ModelCandidateRouteDecision(primaryOption)

        ProviderRepository.ensureBuiltInsMerged()
        val providers = ProviderRepository.allProviders()
        val candidates = buildCandidateRouteOptions(
            primary = primary,
            settings = settings,
            fallback = SettingsDataStore.modelFallbackSettings(),
            providers = providers,
            purpose = requirements.purpose,
        )
        return ModelCandidateSelector.select(primaryOption, candidates, requirements)
    }

    private fun buildCandidateRouteOptions(
        primary: AgentModelClient.ModelConfig,
        settings: ModelCandidateSettings,
        fallback: ModelFallbackSettings,
        providers: List<ProviderSetting>,
        purpose: ModelCandidatePurpose,
    ): List<ModelCandidateRouteOption> = buildList {
        settings.candidates.filter { it.enabled && it.purpose.matches(purpose) }.forEach { binding ->
            val resolved = providers.enabledModel(binding.modelId, binding.providerId) ?: return@forEach
            add(ModelCandidateRouteOption(
                providerId = resolved.provider.id,
                modelId = resolved.model.id,
                config = routedCandidateConfig(primary, resolved.provider, resolved.model),
                source = ModelCandidateSource.USER_CANDIDATE,
                authorized = true,
                purpose = binding.purpose,
                requiredTools = binding.requiredTools,
                requiredVision = binding.requiredVision,
                minimumContextWindow = binding.minimumContextWindow,
                priority = binding.priority,
            ))
        }
        if (fallback.enabled && !fallback.modelId.isNullOrBlank()) {
            providers.enabledModel(fallback.modelId, fallback.providerId)?.let { resolved ->
                add(ModelCandidateRouteOption(
                    providerId = resolved.provider.id,
                    modelId = resolved.model.id,
                    config = routedCandidateConfig(primary, resolved.provider, resolved.model),
                    source = ModelCandidateSource.FALLBACK,
                    authorized = true,
                ))
            }
        }
    }

    private fun routedCandidateConfig(
        primary: AgentModelClient.ModelConfig,
        provider: ProviderSetting,
        model: Model,
    ): AgentModelClient.ModelConfig {
        val base = buildRuntimeConfig(provider, model)
        val effort = base.reasoningCapabilities?.normalize(primary.effectiveReasoningEffort)
            ?: base.effectiveReasoningEffort
        return base.copy(
            terminalTools = primary.terminalTools,
            browserTools = primary.browserTools,
            deviceDirectTools = primary.deviceDirectTools,
            deviceSensitiveReadTools = primary.deviceSensitiveReadTools,
            deviceSensitiveActionTools = primary.deviceSensitiveActionTools,
            subagentTools = primary.subagentTools,
            thinkingEnabled = effort.enablesReasoning,
            reasoningEffort = effort,
            autoCompactionEnabled = primary.autoCompactionEnabled,
            supportsSearchSuggestions = primary.supportsSearchSuggestions,
        )
    }

    private fun ModelCandidatePurpose.matches(requested: ModelCandidatePurpose): Boolean =
        this == ModelCandidatePurpose.ALL || this == requested

    /**
     * 解析 Subagent 实际运行所用的模型配置。
     *
     * 指定了独立子模型且该模型仍处于启用状态时优先使用子模型，否则平滑回退到主模型；
     * 工具开关继承父请求，并强制关闭 `subagentTools` 防止递归派发。
     * 会议参与者复用同一解析链，但显式传入 `MEETING` 用途与自己的候选路由开关。
     */
    suspend fun subagentRuntimeConfig(
        primaryConfig: AgentModelClient.ModelConfig,
        profileName: String,
        explicitProviderId: String? = null,
        explicitModelId: String? = null,
        explicitReasoningEffort: ReasoningEffort? = null,
        allowCandidateRouting: Boolean = false,
        candidatePurpose: ModelCandidatePurpose = ModelCandidatePurpose.SUBAGENT,
        requiresTools: Boolean = true,
        requiresVision: Boolean = false,
        estimatedInputTokens: Int = 0,
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
            allowCandidateRouting = allowCandidateRouting,
            candidatePurpose = candidatePurpose,
            requiresTools = requiresTools,
            requiresVision = requiresVision,
            estimatedInputTokens = estimatedInputTokens,
            candidateSettings = SettingsDataStore.modelCandidateSettings(),
            fallbackSettings = SettingsDataStore.modelFallbackSettings(),
            useBuiltinDefaults = io.github.mangi.eta.agent.subagent.SubagentProfileCatalog.get(profileName)?.builtin != false,
        )
    }

    /**
     * 自动任务运行配置。
     *
     * 仅在显式持久开关开启时复用候选路由，且用途固定为 [ModelCandidatePurpose.AUTOMATION]。
     * 开关关闭时保持原有的子 Agent Profile、全局与主会话模型优先级。
     */
    suspend fun automationRuntimeConfig(
        primaryConfig: AgentModelClient.ModelConfig,
        profileName: String,
        requiresTools: Boolean = true,
        requiresVision: Boolean = false,
        estimatedInputTokens: Int = 0,
    ): ResolvedSubagentConfig {
        val settings = SettingsDataStore.automationModelSettings()
        return subagentRuntimeConfig(
            primaryConfig = primaryConfig,
            profileName = profileName,
            allowCandidateRouting = settings.candidateRoutingEnabled,
            candidatePurpose = ModelCandidatePurpose.AUTOMATION,
            requiresTools = requiresTools,
            requiresVision = requiresVision,
            estimatedInputTokens = estimatedInputTokens,
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
        allowCandidateRouting: Boolean = false,
        candidatePurpose: ModelCandidatePurpose = ModelCandidatePurpose.SUBAGENT,
        requiresTools: Boolean = true,
        requiresVision: Boolean = false,
        /** 调用方已知的本次请求输入估算；确实拿不到时保持 0，不伪造。 */
        estimatedInputTokens: Int = 0,
        candidateSettings: ModelCandidateSettings = ModelCandidateSettings(),
        fallbackSettings: ModelFallbackSettings = ModelFallbackSettings(),
        useBuiltinDefaults: Boolean = true,
    ): ResolvedSubagentConfig {
        val normalizedProfile = profileName.trim().lowercase()
        val builtinProfile = io.github.mangi.eta.agent.subagent.SubagentProfile.BUILTIN_PROFILES.takeIf { useBuiltinDefaults }.orEmpty()
            .firstOrNull { it.name == normalizedProfile }
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
        var modelSource = selected?.attempt?.source ?: SubagentConfigSource.PRIMARY
        var base = selected?.let { buildRuntimeConfig(it.provider, it.model) } ?: primaryConfig
        // 自动任务使用自己的持久开关作为唯一启用条件，不依赖主聊天候选路由的总开关。
        val candidateRoutingEnabled = allowCandidateRouting &&
            (candidateSettings.enabled || candidatePurpose == ModelCandidatePurpose.AUTOMATION) &&
            candidateRoutingGate(candidatePurpose, profileBinding, settings)
        if (candidateRoutingEnabled) {
            val primaryOption = ModelCandidateRouteOption(
                providerId = base.providerId,
                modelId = selected?.model?.id ?: primaryConfig.model,
                config = base,
                source = ModelCandidateSource.PRIMARY,
                authorized = true,
            )
            val decision = ModelCandidateSelector.select(
                primary = primaryOption,
                candidates = buildCandidateRouteOptions(
                    primary = primaryConfig,
                    settings = candidateSettings,
                    fallback = fallbackSettings,
                    providers = providers,
                    purpose = candidatePurpose,
                ),
                request = ModelCandidateRouteRequest(
                    purpose = candidatePurpose,
                    estimatedInputTokens = estimatedInputTokens,
                    requiredTools = requiresTools,
                    requiredVision = requiresVision,
                ),
            )
            val routed = decision.selected ?: throw IllegalStateException(
                "${candidatePurpose.routingSubjectLabel()}无合格候选模型，已取消启动：" + decision.rejections
                    .joinToString("；") { rejection ->
                        "${rejection.providerId}/${rejection.modelId} ${rejection.reason.subagentLabel()}"
                    },
            )
            if (routed.source != ModelCandidateSource.PRIMARY) {
                base = requireNotNull(routed.config) { "候选模型不可用" }
                modelSource = SubagentConfigSource.CANDIDATE
            }
        } else {
            // 未启用候选路由时，所选模型（显式/Profile/全局/主会话）仍需在写出 Runtime 前
            // 通过同一个能力判定。图片无法像工具那样安全降级，因此必须提前失败关闭。
            val visionRejection = ModelCandidateSelector.capabilityRejection(
                config = base,
                request = ModelCandidateRouteRequest(
                    purpose = candidatePurpose,
                    estimatedInputTokens = estimatedInputTokens,
                    requiredTools = false,
                    requiredVision = requiresVision,
                ),
            )
            if (visionRejection != null) {
                throw IllegalStateException(
                    "${candidatePurpose.routingSubjectLabel()}所选模型 ${base.providerId}/${base.model} " +
                        "${visionRejection.subagentLabel()}，已取消启动",
                )
            }
        }

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
        val contextWindowOverride = (profileBinding?.contextWindow ?: builtinProfile?.defaultContextWindow)?.let { requested ->
            if (requested > 0) {
                requested
            } else {
                fallbackReasons += "Profile $normalizedProfile 的上下文预算必须大于 0"
                null
            }
        }
        // Catalog windows are metadata until the user configures a model. Subagent negotiation
        // still needs that metadata as a hard ceiling so a Profile cannot claim a larger window.
        val baseContextWindow = base.knownContextWindow ?: base.contextWindow
        val negotiatedContextWindow = when {
            contextWindowOverride == null -> base.contextWindow
            baseContextWindow == null -> contextWindowOverride
            else -> minOf(baseContextWindow, contextWindowOverride)
        }
        val maxToolCalls = (profileBinding?.maxToolCalls ?: builtinProfile?.defaultMaxToolCalls)?.let { requested ->
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
                SubagentConfigSource.CANDIDATE -> "候选路由"
                SubagentConfigSource.PRIMARY -> "主会话"
            }
            return "$label 的模型不可用（$target）"
        }
    }

    private fun String.subagentLabel(): String = when (this) {
        "disabled" -> "已停用"
        "unauthorized" -> "未授权或连接不可用"
        "unavailable" -> "模型不可用"
        "purpose_mismatch" -> "用途不匹配"
        "tools_required" -> "不支持工具"
        "vision_required" -> "不支持视觉"
        "context_window_below_minimum" -> "上下文窗口不足"
        "input_exceeds_context" -> "输入超出上下文"
        else -> this
    }

    private fun ModelCandidatePurpose.routingSubjectLabel(): String = when (this) {
        ModelCandidatePurpose.MEETING -> "会议参与者"
        ModelCandidatePurpose.SUBAGENT -> "子 Agent"
        ModelCandidatePurpose.AUTOMATION -> "自动任务"
        else -> "模型候选路由"
    }

    /**
     * 会议与自动任务各自持有独立的持久开关，调用方已经通过 [allowCandidateRouting] 传入；
     * 子 Agent 继续沿用 Profile 覆盖或全局开关。
     */
    private fun candidateRoutingGate(
        purpose: ModelCandidatePurpose,
        profileBinding: SubagentProfileBinding?,
        settings: SubagentSettings,
    ): Boolean = when (purpose) {
        ModelCandidatePurpose.MEETING, ModelCandidatePurpose.AUTOMATION -> true
        else -> profileBinding?.candidateRoutingEnabled ?: settings.candidateRoutingEnabled
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
        // 手工/Profile/全局绑定与候选、备用、会话选择共用同一凭据判定：
        // 没有可用凭据的服务商不能被显式绑定绕过。
        val enabledProviders = providers.filter(ProviderSetting::hasUsableConnection)
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
        if (model != null) io.github.mangi.eta.agent.control.NexusSettingsControlPlane.get(SettingsDataStore.applicationContext()).applyJson(
            "settings.current_model", org.json.JSONObject().put("providerId", provider?.id).put("modelId", model.id),
            io.github.zhh_lgtm.nexus.application.api.SettingsActorKind.LOCAL_USER)
        else SettingsDataStore.setSelection(null, null)
        ProviderRepository.repairSelection()
    }

    suspend fun setSelectedModelId(id: String?) {
        val provider = id?.let { ProviderRepository.providerByModelId(it) }
            ?.takeIf { it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == id && it.isEnabled }
        if (model != null) io.github.mangi.eta.agent.control.NexusSettingsControlPlane.get(SettingsDataStore.applicationContext()).applyJson(
            "settings.current_model", org.json.JSONObject().put("providerId", provider?.id).put("modelId", model.id),
            io.github.zhh_lgtm.nexus.application.api.SettingsActorKind.LOCAL_USER)
        else SettingsDataStore.setSelection(null, null)
        ProviderRepository.repairSelection()
    }

    suspend fun currentRuntimeConfig(): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        val settings = ProviderRepository.repairSelection()
        val providers = ProviderRepository.allProviders()
        val selected = providers.enabledModel(settings.selectedModelId, settings.selectedProviderId)
        val resolved = selected ?: providers.asSequence()
            .filter(ProviderSetting::hasUsableConnection)
            .mapNotNull { provider ->
                provider.selectedOrFirstModel(null)?.let { model ->
                    ProviderModel(provider, model)
                }
            }
            .firstOrNull()
            ?: return null
        return buildRuntimeConfig(resolved.provider, resolved.model)
    }

    /** 会话绑定的模型仍可用时使用它，否则回落到默认模型。 */
    suspend fun runtimeConfigFor(modelId: String?): AgentModelClient.ModelConfig? {
        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.allProviders().enabledModel(modelId)?.let { (provider, model) ->
            return buildRuntimeConfig(provider, model)
        }
        return currentRuntimeConfig()
    }

    /** A chat submission uses the model shown when the user pressed send. */
    suspend fun runtimeConfigForSelection(modelId: String?): AgentModelClient.ModelConfig? {
        if (modelId == null) return null
        ProviderRepository.ensureBuiltInsMerged()
        val selected = ProviderRepository.allProviders().enabledModel(modelId) ?: return null
        return buildRuntimeConfig(selected.provider, selected.model)
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

    suspend fun ensureDefaults(service: XposedService?) = defaultsMutex.withLock {
        if (!localDefaultsReady) {
            ProviderRepository.ensureBuiltInsMerged()
            ProviderRepository.repairSelection()
            localDefaultsReady = true
        }
        if (service == null) return@withLock
        if (defaultsServiceSynced && lastDefaultsService === service) return@withLock
        if (syncToRemotePreferences(service)) {
            lastDefaultsService = service
            defaultsServiceSynced = true
        }
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
            supportsTools = model.supportsTools,
            supportsImageInput = model.supportsVision,
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
            if (model.reasoningOverride == false) return null
            if (model.reasoningOverride == true) return model.reasoningCapabilitiesOverride
                ?: GeminiModelCapabilities.reasoningCapabilities(model.modelId)
                ?: ModelReasoningCapabilities()
            if (model.reasoning == false) return null
            return model.reasoningCapabilities ?: GeminiModelCapabilities.reasoningCapabilities(model.modelId)
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
