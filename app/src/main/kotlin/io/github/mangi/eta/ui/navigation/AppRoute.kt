package io.github.mangi.eta.ui.navigation

import kotlinx.serialization.Serializable
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import top.yukonga.miuix.kmp.nav.core.NavKey

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data object AppControlCenter : AppRoute

    @Serializable
    data class Browser(val sessionKey: String = BrowserSessionRegistry.MANUAL_SESSION_KEY) : AppRoute

    @Serializable
    data object Terminal : AppRoute

    @Serializable
    data object Tools : AppRoute

    @Serializable
    data object WebToolsSettings : AppRoute

    @Serializable
    data object Skills : AppRoute

    @Serializable
    data object PromptInjection : AppRoute

    @Serializable
    data object CapabilityCenter : AppRoute

    @Serializable
    data object ApiConnectors : AppRoute

    @Serializable
    data object ConnectorCenter : AppRoute

    @Serializable
    data object Subagents : AppRoute

    @Serializable
    data object TaskCenter : AppRoute

    @Serializable
    data object A2AAgents : AppRoute

    @Serializable
    data object SelfLearning : AppRoute

    @Serializable
    data object Characters : AppRoute

    @Serializable
    data class CharacterDetail(val characterId: String) : AppRoute

    @Serializable
    data class CharacterEditor(val characterId: String? = null) : AppRoute

    @Serializable
    data object CharacterPersona : AppRoute

    @Serializable
    data class CharacterMemory(val characterId: String) : AppRoute

    @Serializable
    data object Permissions : AppRoute

    @Serializable
    data object SystemEnhance : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data object AppearanceSettings : AppRoute

    @Serializable
    data object ConversationArchiveSettings : AppRoute

    @Serializable
    data class MeetingRooms(val openCreateOnStart: Boolean = false) : AppRoute

    @Serializable
    data class MeetingRoom(val roomId: String) : AppRoute

    @Serializable
    data object MeetingSettings : AppRoute

    @Serializable
    data object SpeechSettings : AppRoute

    @Serializable
    data object SpeechRecognition : AppRoute

    @Serializable
    data object SpeechSynthesis : AppRoute

    @Serializable
    data object SpeechOss : AppRoute

    @Serializable
    data object DataBackup : AppRoute

    @Serializable
    data object Memory : AppRoute

    @Serializable
    data object LinuxEnvironment : AppRoute

    @Serializable
    data object SharedFolders : AppRoute

    @Serializable
    data object Workspace : AppRoute

    @Serializable
    data object Projects : AppRoute

    @Serializable
    data class LinuxFiles(val distribution: String) : AppRoute

    @Serializable
    data object ModelProviders : AppRoute

    @Serializable
    data object LocalModels : AppRoute

    @Serializable
    data object CommunityCatalog : AppRoute

    @Serializable
    data class CommunityCatalogProvider(val catalogId: String) : AppRoute

    @Serializable
    data object McpServers : AppRoute

    @Serializable
    data object AgentMetrics : AppRoute
    @Serializable
    data class ConversationBranches(val conversationId: String? = null) : AppRoute

    @Serializable
    data class ConversationInsights(
        val conversationId: String = "",
        val meetingRoomId: String = "",
    ) : AppRoute

    @Serializable
    data class AgentMetricDetail(val recordId: String) : AppRoute

    @Serializable
    data object AgentTokenPricing : AppRoute

    @Serializable
    data class AgentTokenPriceEditor(val providerId: String, val modelId: String) : AppRoute

    @Serializable
    data class McpServerDetail(val serverId: String) : AppRoute

    @Serializable
    data class McpCatalog(val serverId: String) : AppRoute

    @Serializable
    data class ModelProviderDetail(val providerId: String) : AppRoute

    @Serializable
    data class ModelProviderNew(val providerType: NewProviderType) : AppRoute
}

@Serializable
enum class NewProviderType { OpenAiCompatible, LocalRuntime, Anthropic, Gemini }
