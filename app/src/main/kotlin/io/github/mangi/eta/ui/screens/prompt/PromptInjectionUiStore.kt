package io.github.mangi.eta.ui.screens.prompt

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.prompt.PromptInjectionConfig
import io.github.mangi.eta.agent.prompt.PromptInjectionStore
import io.github.mangi.eta.agent.prompt.PromptAnalysisReport
import io.github.mangi.eta.agent.prompt.PromptConflictAnalyzer
import io.github.mangi.eta.agent.prompt.PromptScopeBinding
import io.github.mangi.eta.agent.prompt.PromptScopeType
import io.github.mangi.eta.agent.prompt.PromptTarget
import io.github.mangi.eta.agent.prompt.PromptTemplate
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.fork.meeting.MeetingDatabase
import io.github.mangi.eta.fork.meeting.MeetingParticipantEntity
import io.github.mangi.eta.fork.meeting.MeetingRoomEntity
import io.github.mangi.eta.fork.tasks.AgentTaskDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal data class PromptConversationOption(val id: String, val title: String, val updatedAt: Long)

internal data class PromptSubagentOption(val name: String, val summary: String)

internal data class PromptParticipantOption(
    val profileName: String,
    val displayName: String,
    val enabled: Boolean,
)

internal data class PromptMeetingOption(
    val id: String,
    val title: String,
    val participants: List<PromptParticipantOption>,
)

internal data class PromptCharacterOption(val id: String, val title: String)

internal data class PromptAutomationOption(val id: String, val title: String)

/** One selectable binding target, already resolved to human-readable metadata. */
internal data class PromptScopeTargetUi(
    val target: PromptTarget,
    val title: String,
    val detail: String = "",
    val enabled: Boolean = true,
)

private data class PromptScopeSnapshot(
    val config: PromptInjectionConfig,
    val providers: List<ProviderSetting>,
    val conversations: List<PromptConversationOption>,
    val subagents: List<PromptSubagentOption>,
    val meetings: List<PromptMeetingOption>,
    val characters: List<PromptCharacterOption>,
    val automations: List<PromptAutomationOption>,
)

/**
 * Read/write facade for the prompt library UI.
 *
 * Scope data is loaded read-only from the existing repositories and databases. Writes go through
 * [PromptInjectionStore], which remains the single source of truth for runtime resolution.
 */
internal class PromptInjectionUiStore(context: Context) {
    private val appContext = context.applicationContext

    var config by mutableStateOf(PromptInjectionConfig())
        private set
    var providers by mutableStateOf<List<ProviderSetting>>(emptyList())
        private set
    var conversations by mutableStateOf<List<PromptConversationOption>>(emptyList())
        private set
    var subagents by mutableStateOf<List<PromptSubagentOption>>(emptyList())
        private set
    var meetings by mutableStateOf<List<PromptMeetingOption>>(emptyList())
        private set
    var characters by mutableStateOf<List<PromptCharacterOption>>(emptyList())
        private set
    var automations by mutableStateOf<List<PromptAutomationOption>>(emptyList())
        private set
    var loading by mutableStateOf(true)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var validationError by mutableStateOf<String?>(null)
        private set

    suspend fun refresh() {
        loading = true
        val snapshot = withContext(Dispatchers.IO) { loadSnapshot() }
        config = snapshot.config
        providers = snapshot.providers
        conversations = snapshot.conversations
        subagents = snapshot.subagents
        meetings = snapshot.meetings
        characters = snapshot.characters
        automations = snapshot.automations
        loading = false
    }

    suspend fun saveTemplate(template: PromptTemplate): Boolean {
        val report = PromptConflictAnalyzer.analyzeTemplate(template)
        validationError = report.errors.joinToString("\n") { it.message }.takeIf(String::isNotBlank)
        if (!report.canSave) {
            status = validationError
            return false
        }
        return mutate {
            PromptInjectionStore.saveTemplate(template)
        }.also { succeeded ->
            if (succeeded) {
                status = report.warnings.firstOrNull()?.message
                    ?: report.issues.firstOrNull()?.message
            }
        }
    }

    suspend fun deleteTemplate(templateId: String): Boolean = mutate {
        PromptInjectionStore.deleteTemplate(templateId)
    }

    suspend fun bind(templateId: String, target: PromptTarget): Boolean = mutate {
        PromptInjectionStore.bind(templateId, target)
    }

    suspend fun unbind(target: PromptTarget): Boolean = mutate {
        PromptInjectionStore.unbind(target)
    }

    suspend fun replaceConfig(next: PromptInjectionConfig): Boolean {
        val report = PromptConflictAnalyzer.analyzeConfig(next)
        validationError = report.errors.joinToString("\n") { it.message }.takeIf(String::isNotBlank)
        if (!report.canSave) {
            status = validationError
            return false
        }
        return mutate {
            PromptInjectionStore.replace(next)
        }.also { succeeded ->
            if (succeeded) status = report.warnings.firstOrNull()?.message
        }
    }

    fun report(message: String?) {
        status = message
    }

    fun analyzeTemplate(template: PromptTemplate): PromptAnalysisReport =
        PromptConflictAnalyzer.analyzeTemplate(template)

    fun analyzeLibrary(): PromptAnalysisReport = PromptConflictAnalyzer.analyzeConfig(config)

    fun template(id: String?): PromptTemplate? =
        id?.let { wanted -> config.templates.firstOrNull { it.id == wanted } }

    fun bindingsFor(templateId: String): List<PromptScopeBinding> =
        config.bindings.filter { it.templateId == templateId }

    fun bindingFor(target: PromptTarget): PromptScopeBinding? =
        config.bindings.firstOrNull { it.target.key == target.key }

    fun boundScopeCount(): Int = config.bindings.size

    fun enabledCharacterCount(): Int =
        config.templates.filter(PromptTemplate::enabled).sumOf(PromptTemplate::characterCount)

    fun targets(type: PromptScopeType): List<PromptScopeTargetUi> {
        val available = availableTargets(type)
        if (type == PromptScopeType.GLOBAL) return available
        val known = available.mapTo(hashSetOf()) { it.target.key }
        val orphaned = config.bindings
            .asSequence()
            .filter { it.target.type == type && it.target.key !in known }
            .map { binding ->
                val target = binding.target
                PromptScopeTargetUi(
                    target = target,
                    title = targetTitle(target).ifBlank { target.primaryId },
                    detail = targetDetail(target),
                    enabled = false,
                )
            }
            .toList()
        return available + orphaned
    }

    private fun availableTargets(type: PromptScopeType): List<PromptScopeTargetUi> = when (type) {
        PromptScopeType.GLOBAL -> emptyList()
        PromptScopeType.MODEL -> providers.flatMap { provider ->
            provider.models.filter { it.modelId.isNotBlank() }.filter { provider.id.isNotBlank() }.map { model ->
                PromptScopeTargetUi(
                    target = PromptTarget.model(provider.id, model.modelId),
                    title = provider.name.ifBlank { provider.id },
                    detail = model.displayName.ifBlank { model.modelId } + " · " + model.modelId,
                    enabled = provider.isEnabled && model.isEnabled,
                )
            }
        }
        PromptScopeType.CONVERSATION -> conversations.filter { it.id.isNotBlank() }.map { conversation ->
            PromptScopeTargetUi(
                target = PromptTarget.conversation(conversation.id),
                title = conversation.title.ifBlank { conversation.id },
                detail = conversation.id,
            )
        }
        PromptScopeType.SUBAGENT -> subagents.filter { it.name.isNotBlank() }.map { subagent ->
            PromptScopeTargetUi(
                target = PromptTarget.subagent(subagent.name),
                title = subagent.name,
                detail = subagent.summary,
            )
        }
        PromptScopeType.MEETING -> meetings.filter { it.id.isNotBlank() }.map { meeting ->
            PromptScopeTargetUi(
                target = PromptTarget.meeting(meeting.id),
                title = meeting.title.ifBlank { meeting.id },
                detail = meeting.participants.joinToString("、") { it.displayName },
            )
        }
        PromptScopeType.MEETING_PARTICIPANT -> meetings.flatMap { meeting ->
            meeting.participants.filter { it.profileName.isNotBlank() }.map { participant ->
                PromptScopeTargetUi(
                    target = PromptTarget.meetingParticipant(meeting.id, participant.profileName),
                    title = participant.displayName.ifBlank { participant.profileName },
                    detail = meeting.title + " · " + participant.profileName,
                    enabled = participant.enabled,
                )
            }
        }
        PromptScopeType.CHARACTER -> characters.filter { it.id.isNotBlank() }.map { character ->
            PromptScopeTargetUi(
                target = PromptTarget.character(character.id),
                title = character.title.ifBlank { character.id },
                detail = character.id,
            )
        }
        PromptScopeType.AUTOMATION -> automations.filter { it.id.isNotBlank() }.map { automation ->
            PromptScopeTargetUi(
                target = PromptTarget.automation(automation.id),
                title = automation.title.ifBlank { automation.id },
                detail = automation.id,
            )
        }
    }

    fun targetTitle(target: PromptTarget): String = when (target.type) {
        PromptScopeType.GLOBAL -> ""
        PromptScopeType.MODEL -> {
            val provider = providers.firstOrNull { it.id == target.primaryId }
            val model = provider?.models?.firstOrNull { it.modelId == target.secondaryId }
            val providerName = provider?.name ?: target.primaryId
            val modelName = model?.displayName ?: target.secondaryId
            "$providerName / $modelName"
        }
        PromptScopeType.CONVERSATION -> conversations.firstOrNull { it.id == target.primaryId }
            ?.title?.takeIf(String::isNotBlank) ?: target.primaryId
        PromptScopeType.SUBAGENT -> target.primaryId
        PromptScopeType.MEETING -> meetings.firstOrNull { it.id == target.primaryId }
            ?.title?.takeIf(String::isNotBlank) ?: target.primaryId
        PromptScopeType.MEETING_PARTICIPANT -> {
            val room = meetings.firstOrNull { it.id == target.primaryId }
            val participant = room?.participants?.firstOrNull { it.profileName == target.secondaryId }
            val roomName = room?.title?.takeIf(String::isNotBlank) ?: target.primaryId
            val person = participant?.displayName ?: target.secondaryId
            "$roomName / $person"
        }
        PromptScopeType.CHARACTER -> characters.firstOrNull { it.id == target.primaryId }
            ?.title?.takeIf(String::isNotBlank) ?: target.primaryId
        PromptScopeType.AUTOMATION -> automations.firstOrNull { it.id == target.primaryId }
            ?.title?.takeIf(String::isNotBlank) ?: target.primaryId
    }

    fun targetDetail(target: PromptTarget): String = when (target.type) {
        PromptScopeType.CONVERSATION -> target.primaryId
        PromptScopeType.MODEL -> target.secondaryId
        PromptScopeType.MEETING_PARTICIPANT -> target.secondaryId
        PromptScopeType.CHARACTER, PromptScopeType.AUTOMATION -> target.primaryId
        else -> ""
    }

    private suspend fun mutate(operation: () -> Boolean): Boolean {
        val succeeded = withContext(Dispatchers.IO) {
            runCatching(operation).getOrDefault(false)
        }
        if (succeeded) {
            config = withContext(Dispatchers.IO) {
                runCatching { PromptInjectionStore.load() }.getOrDefault(config)
            }
        }
        return succeeded
    }

    private suspend fun loadSnapshot(): PromptScopeSnapshot {
        val loadedConfig = runCatching { PromptInjectionStore.load() }
            .getOrDefault(PromptInjectionConfig())
        val loadedProviders = runCatching { ProviderRepository.allProviders() }
            .getOrDefault(emptyList())
        val loadedConversations = runCatching {
            EtaDatabase.get(appContext).conversationDao().conversationIdentities()
                .sortedByDescending { it.updatedAt }
                .take(500)
                .map { PromptConversationOption(id = it.id, title = it.title, updatedAt = it.updatedAt) }
        }.getOrDefault(emptyList())
        val loadedSubagents = runCatching {
            SubagentProfileCatalog.listAll().map { profile ->
                PromptSubagentOption(name = profile.name, summary = profile.summary)
            }
        }.getOrDefault(emptyList())
        val loadedMeetings = runCatching {
            val dao = MeetingDatabase.get(appContext).meetingDao()
            dao.roomsFlow().first()
                .filter { it.archivedAt == null }
                .map { room -> meetingOption(room, dao.participants(room.id)) }
        }.getOrDefault(emptyList())
        val loadedCharacters = runCatching {
            EtaDatabase.get(appContext).characterDao().characterRows()
                .filterNot { it.archived }
                .sortedByDescending { it.updatedAt }
                .map { character -> PromptCharacterOption(character.id, character.name) }
        }.getOrDefault(emptyList())
        val loadedAutomations = runCatching {
            AgentTaskDatabase.get(appContext).agentTaskDao().definitionsNow()
                .map { definition -> PromptAutomationOption(definition.id, definition.title) }
        }.getOrDefault(emptyList())
        return PromptScopeSnapshot(
            config = loadedConfig,
            providers = loadedProviders,
            conversations = loadedConversations,
            subagents = loadedSubagents,
            meetings = loadedMeetings,
            characters = loadedCharacters,
            automations = loadedAutomations,
        )
    }

    private fun meetingOption(
        room: MeetingRoomEntity,
        participants: List<MeetingParticipantEntity>,
    ): PromptMeetingOption = PromptMeetingOption(
        id = room.id,
        title = room.title,
        participants = participants.map { participant ->
            PromptParticipantOption(
                profileName = participant.profileName,
                displayName = participant.displayName,
                enabled = participant.enabled,
            )
        },
    )
}
