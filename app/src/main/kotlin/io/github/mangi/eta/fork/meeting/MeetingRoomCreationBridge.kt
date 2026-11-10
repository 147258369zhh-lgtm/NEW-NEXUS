package io.github.mangi.eta.fork.meeting

import android.content.Context
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
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
)

internal data class MeetingModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
    val displayName: String,
) {
    val label: String get() = "$providerName · ${displayName.ifBlank { modelName }}"
}

internal data class MeetingRoomCreationOptions(
    val profiles: List<MeetingProfileOption>,
    val models: List<MeetingModelOption>,
)

internal data class MeetingRoomSelectionDraft(
    val title: String,
    val hostProfile: String,
    val participantProfiles: Set<String>,
    val modelOverrides: Map<String, MeetingModelOption> = emptyMap(),
) {
    fun validate(): Boolean =
        title.isNotBlank() && hostProfile.isNotBlank() && participantProfiles.isNotEmpty()
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
        val profiles = (localProfiles + remoteProfiles).distinctBy(MeetingProfileOption::name)
        val models = ProviderRepository.allProviders()
            .filter { provider -> provider.isEnabled }
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
                        )
                    }
            }
            .distinctBy { option -> option.providerId to option.modelId }
        MeetingRoomCreationOptions(profiles, models)
    }

    suspend fun createRoom(
        context: Context,
        draft: MeetingRoomSelectionDraft,
        profiles: List<MeetingProfileOption>,
    ): String {
        require(draft.validate()) { "会议室名称、主持人和至少一位参与者不能为空" }
        val byName = profiles.associateBy { it.name }
        fun participant(name: String, isHost: Boolean): MeetingParticipantDraft {
            val profile = byName[name]
            val override = draft.modelOverrides[name]
            return MeetingParticipantDraft(
                profileName = name,
                displayName = profile?.name ?: name,
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
        )
    }

    internal fun profileOptions(profiles: List<SubagentProfile>): List<MeetingProfileOption> =
        profiles.map { profile ->
            MeetingProfileOption(
                name = profile.name,
                summary = profile.summary,
                builtIn = profile.builtin,
            )
        }
}
