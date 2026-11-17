package io.github.mangi.eta.fork.meeting

import android.content.Context
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.data.model.hasUsableConnection
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.fork.a2a.A2ADatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal data class MeetingProfileOption(
    val name: String,
    val summary: String,
    val builtIn: Boolean,
    val remoteAgentId: String? = null,
    val displayName: String = name,
)

internal data class MeetingModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
    val displayName: String,
    val supportsTools: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,
    val contextWindow: Int? = null,
    val structuredOutput: Boolean = false,
    val preferenceOrder: Int = 0,
) {
    val label: String get() = "$providerName · ${displayName.ifBlank { modelName }}"
    /** Provider aliases of the same advertised model do not create independent models. */
    val diversityIdentity: String get() = modelName.trim().lowercase()
}

internal data class MeetingRoomCreationOptions(
    val profiles: List<MeetingProfileOption>,
    val models: List<MeetingModelOption>,
    val preferredModels: Map<String, MeetingModelOption> = emptyMap(),
    val unavailablePreferredProfiles: Set<String> = emptySet(),
    val defaultHostProfile: String = SubagentProfile.TYPE_MEETING_HOST,
)

/** Room-local behavior snapshot. Never changes the user's saved Agent template. */
internal data class MeetingHostOverride(val displayName: String, val instruction: String) {
    fun validate() = displayName.isNotBlank() && displayName.length <= 80 && instruction.isNotBlank() && instruction.length <= 16_000
    fun encode() = org.json.JSONObject().put("display_name", displayName).put("instruction", instruction)
    companion object {
        fun decode(value: org.json.JSONObject?): MeetingHostOverride? = value?.let {
            MeetingHostOverride(it.optString("display_name"), it.optString("instruction")).takeIf(MeetingHostOverride::validate)
        }
    }
}

internal data class MeetingRoomSelectionDraft(
    val title: String,
    val hostProfile: String,
    val participantProfiles: Set<String>,
    val modelOverrides: Map<String, MeetingModelOption> = emptyMap(),
    val templateId: String? = null,
    val templateVersion: Int = 1,
    val hostOverride: MeetingHostOverride? = null,
) {
    fun validate(): Boolean =
        title.isNotBlank() && hostProfile.isNotBlank() && hostOverride?.validate() != false
}

internal object MeetingRoomCreationBridge {
    suspend fun loadOptions(context: Context): MeetingRoomCreationOptions = withContext(Dispatchers.IO) {
        ProviderRepository.ensureBuiltInsMerged()
        val localProfiles = SubagentProfileCatalog.listAll()
            .map { profile ->
                MeetingProfileOption(
                    name = profile.name,
                    summary = profile.summary,
                    builtIn = profile.builtin,
                    displayName = profile.displayName,
                )
            }
        val remoteProfiles = A2ADatabase.get(context).dao().observeAgents().first()
            .filter { it.enabled }
            .map { agent ->
                MeetingProfileOption(
                    name = "${agent.displayName} · A2A",
                    summary = agent.endpointUrl,
                    builtIn = false,
                    remoteAgentId = agent.id,
                )
            }
        val profiles = mergeMeetingProfiles(localProfiles, remoteProfiles)
        val models = ProviderRepository.allProviders()
            // 会议室可选模型与主会话选择器、子 Agent/自动任务解析共用同一凭据判定。
            .filter { provider -> provider.hasUsableConnection() }
            .flatMap { provider ->
                provider.models
                    .filter { model -> model.isEnabled }
                    .map { model ->
                        MeetingModelOption(
                            providerId = provider.id,
                            providerName = provider.name,
                            modelId = model.id,
                            modelName = model.modelId,
                            displayName = model.displayName,
                            supportsTools = model.supportsTools,
                            supportsVision = model.supportsVision,
                            supportsReasoning = model.supportsReasoning,
                            contextWindow = model.effectiveContextWindow ?: model.contextWindow,
                            structuredOutput = model.structuredOutput == true,
                            preferenceOrder = provider.sortOrder * 1_000 + model.sortOrder,
                        )
                    }
            }
            .distinctBy { option -> option.providerId to option.modelId }
        val settings = io.github.mangi.eta.data.repository.RuntimeConfigRepository.subagentSettings()
        val bindings = settings.profiles
            .filterValues { !it.modelId.isNullOrBlank() || !it.providerId.isNullOrBlank() }
        val preferred = profiles.mapNotNull { profile -> bindings[profile.name.lowercase()]?.let { binding ->
            models.firstOrNull { (binding.providerId.isNullOrBlank() || it.providerId == binding.providerId) &&
                (binding.modelId.isNullOrBlank() || it.modelId == binding.modelId) }?.let { profile.name to it }
        } }.toMap()
        MeetingRoomCreationOptions(profiles, models, preferred,
            profiles.map { it.name }.filter { it.lowercase() in bindings && it !in preferred }.toSet(),
            resolveDefaultHost(settings.defaultMeetingHostProfile, profiles))
    }

    suspend fun createRoom(
        context: Context,
        draft: MeetingRoomSelectionDraft,
        profiles: List<MeetingProfileOption>,
    ): String {
        require(draft.validate()) { "会议室名称和主持人不能为空，临时主持职责须完整" }
        // Reload at the commit boundary: a model disabled while the dialog was open is not usable.
        val options = loadOptions(context)
        val selectedNames = draft.participantProfiles + draft.hostProfile
        require(selectedNames.all { name -> options.profiles.any { it.name == name } }) { "所选参与者已不可用，请重新选择" }
        val bindings = automaticBindings(draft, options)
        val workflow = draft.templateId?.let(MeetingWorkflowCatalog::find)
        require(draft.templateId == null || workflow != null) { "会议用途不可用" }
        val byName = options.profiles.associateBy { it.name }
        require(draft.hostOverride == null || byName[draft.hostProfile]?.remoteAgentId == null) { "临时主持职责仅支持本机Agent" }
        fun participant(name: String, isHost: Boolean): MeetingParticipantDraft {
            val profile = byName[name]
            val override = bindings[name]
            return MeetingParticipantDraft(
                profileName = name,
                displayName = if (isHost) draft.hostOverride?.displayName ?: profile?.displayName ?: name
                    else profile?.displayName ?: name,
                profileSummary = profile?.summary.orEmpty(),
                providerId = profile?.remoteAgentId?.let { "a2a:$it" } ?: override?.providerId,
                modelId = override?.modelId,
                isHost = isHost,
            )
        }
        val host = participant(draft.hostProfile, isHost = true)
        val selected = (draft.participantProfiles + draft.hostProfile)
            .map { name -> participant(name, isHost = name == draft.hostProfile) }
            .distinctBy(MeetingParticipantDraft::profileName)
        return MeetingRepository.createRoom(
            context = context,
            title = draft.title,
            host = host,
            participants = selected.filterNot { it.profileName == host.profileName },
            templateId = draft.templateId,
            templateVersion = draft.templateVersion,
            hostOverride = draft.hostOverride,
        )
    }

    /** Explicit room and profile choices win. Automatic choices are frozen as real bindings. */
    internal fun automaticBindings(draft: MeetingRoomSelectionDraft, options: MeetingRoomCreationOptions): Map<String, MeetingModelOption> {
        val selected = (listOf(draft.hostProfile) + draft.participantProfiles.sorted()).distinct()
            .filter { name -> options.profiles.firstOrNull { it.name == name }?.remoteAgentId == null }
        val result = linkedMapOf<String, MeetingModelOption>()
        selected.forEach { name ->
            val explicit = draft.modelOverrides[name] ?: options.preferredModels[name]
            if (explicit != null) {
                result[name] = requireNotNull(options.models.firstOrNull { it.providerId == explicit.providerId && it.modelId == explicit.modelId }) {
                    "$name 明确指定的模型已不可用，请重新选择"
                }
            } else require(name !in options.unavailablePreferredProfiles) { "$name 的专属模型配置已失效，请明确选择可用模型" }
        }
        selected.filterNot(result::containsKey).forEach { name ->
            result[name] = chooseModel(options.models, name, name == draft.hostProfile, result.values.toList(),
                requiresTools = name != draft.hostProfile || draft.templateId == "M12")
                ?: error("没有已启用且连接配置可用的模型，请先配置提供商")
        }
        return result
    }

    internal fun chooseModel(models: List<MeetingModelOption>, role: String, isHost: Boolean,
        used: List<MeetingModelOption>, requiresTools: Boolean = !isHost): MeetingModelOption? {
        val counts = used.groupingBy { it.diversityIdentity }.eachCount()
        val capable = models.filter { !requiresTools || it.supportsTools }.ifEmpty { models }
        val diverse = capable.filter { it.diversityIdentity !in counts }.ifEmpty { capable }
        val roleText = role.lowercase()
        val requirements = SubagentProfile.BUILTIN_PROFILES.firstOrNull { it.name == role }?.modelRequirements
        fun score(model: MeetingModelOption): Int =
            (if (model.supportsTools && !isHost) 36 else 0) +
                (if (model.supportsReasoning) if (isHost || requirements?.prefersReasoning == true) 40 else 12 else 0) +
                (if (model.structuredOutput && (isHost || requirements?.prefersStructuredOutput == true)) 28 else 0) +
                (if (model.supportsVision && (requirements?.prefersVision == true || "vision" in roleText || "visual" in roleText || "图" in roleText)) 32 else 0) +
                ((model.contextWindow ?: 0) / 16_384).coerceAtMost(20) - (counts[model.diversityIdentity] ?: 0) * 10
        return diverse.sortedWith(compareByDescending<MeetingModelOption> { score(it) }
            .thenBy { it.preferenceOrder }.thenBy { it.providerId }.thenBy { it.modelId }).firstOrNull()
    }

    internal fun profileOptions(profiles: List<SubagentProfile>): List<MeetingProfileOption> =
        profiles.map { profile ->
            MeetingProfileOption(
                name = profile.name,
                summary = profile.summary,
                builtIn = profile.builtin,
                displayName = profile.displayName,
            )
        }

    internal fun resolveDefaultHost(preferred: String, profiles: List<MeetingProfileOption>): String =
        profiles.firstOrNull { it.name == preferred && it.remoteAgentId == null }?.name
            ?: profiles.firstOrNull { it.name == SubagentProfile.TYPE_MEETING_HOST && it.remoteAgentId == null }?.name
            ?: profiles.firstOrNull { it.remoteAgentId == null }?.name.orEmpty()

    /**
     * Local profiles and A2A agents have separate identities even when they share a visible name.
     * Keep the historical name for the normal case, and disambiguate every remote candidate in a
     * collision with a stable fingerprint of its database id. The participant profile name remains
     * the routing key, so existing rooms and old transcript history keep their original identity.
     */
    internal fun mergeMeetingProfiles(
        localProfiles: List<MeetingProfileOption>,
        remoteProfiles: List<MeetingProfileOption>,
    ): List<MeetingProfileOption> {
        val nameCounts = (localProfiles + remoteProfiles)
            .groupingBy(MeetingProfileOption::name)
            .eachCount()
        val usedNames = linkedSetOf<String>()
        return localProfiles + remoteProfiles.map { profile ->
            val remoteId = profile.remoteAgentId
            if (remoteId == null || nameCounts[profile.name] == 1) {
                require(usedNames.add(profile.name)) { "会议参与者名称冲突：${profile.name}" }
                profile
            } else {
                val base = "${profile.name} · ${stableMemberFingerprint(remoteId)}"
                var candidate = base
                var attempt = 2
                while (!usedNames.add(candidate)) {
                    candidate = "$base-$attempt"
                    attempt++
                }
                profile.copy(name = candidate)
            }
        }
    }

    private fun stableMemberFingerprint(identity: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(8) {
            digest.take(4).forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }
}
