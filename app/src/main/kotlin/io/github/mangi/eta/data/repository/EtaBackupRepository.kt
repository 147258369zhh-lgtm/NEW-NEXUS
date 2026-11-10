package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.ConversationBranchEntity
import io.github.mangi.eta.data.db.ConversationMessageOriginEntity
import io.github.mangi.eta.data.db.ConversationBranchSetupEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ProviderEntity
import io.github.mangi.eta.data.db.ProviderModelEntity
import io.github.mangi.eta.data.db.ProviderWithModelsSeed
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.io.InputStream
import java.io.OutputStream
import io.github.mangi.eta.agent.memory.MemoryEntry
import io.github.mangi.eta.fork.learning.LearningDatabase

/** Eta 用户数据备份；旧备份没有角色字段时保留设备上的角色库。 */
@Serializable
internal data class EtaBackupDocument(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val providers: List<EtaBackupProvider> = emptyList(),
    val catalogRevision: Int = 0,
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val conversations: List<ConversationEntity> = emptyList(),
    val messages: List<ConversationMessageEntity> = emptyList(),
    val contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
    val branches: List<ConversationBranchEntity> = emptyList(),
    val messageOrigins: List<ConversationMessageOriginEntity> = emptyList(),
    val branchSetups: List<ConversationBranchSetupEntity> = emptyList(),
    val conversationState: ConversationStateEntity? = null,
    val memoryMd: String = "",
    val memoryScopes: List<EtaBackupMemoryScope> = emptyList(),
    val memoryEntries: List<MemoryEntry> = emptyList(),
    val roleplay: CharacterBackupData? = null,
    val includedContents: Set<EtaBackupContent> = EtaBackupContent.entries.toSet(),
) {
    companion object {
        const val FORMAT = "eta-backup"
        // 旧版会把未知原生 Provider 当作 OpenAI-compatible，必须通过备份版本阻止错误恢复。
        const val SCHEMA_VERSION = 8
    }
}

@Serializable
internal data class EtaBackupMemoryScope(
    val type: String,
    val id: String,
    val content: String,
)

@Serializable
internal data class EtaBackupProvider(
    val provider: ProviderEntity,
    val models: List<ProviderModelEntity> = emptyList(),
)

internal data class EtaBackupSummary(
    val providerCount: Int,
    val modelCount: Int,
    val conversationCount: Int,
    val messageCount: Int,
    val memoryBytes: Int,
    val characterCount: Int = 0,
    val privateFileCount: Int = 0,
    val privateBytes: Long = 0L,
    val encrypted: Boolean = false,
    val restartRequired: Boolean = false,
    val includedContents: Set<EtaBackupContent> = EtaBackupContent.entries.toSet(),
    val fullArchive: Boolean = false,
)

internal class EtaBackupException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal object EtaBackupRepository {
    private const val EXPORT_PAGE_SIZE = 64

    suspend fun export(context: Context, output: OutputStream,
        contents: Set<EtaBackupContent> = EtaBackupContent.entries.toSet(),
        onProgress: (EtaBackupProgress) -> Unit = {},
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        require(contents.isNotEmpty()) { "请至少选择一种备份内容" }
        var phase = "prepare_export"
        var records = 0L
        var lastProgressAt = 0L
        fun reportProgress(force: Boolean = false) {
            val now = System.nanoTime()
            if (force || now - lastProgressAt >= 200_000_000L) {
                onProgress(EtaBackupProgress(phase = phase, records = records))
                lastProgressAt = now
            }
        }
        try {
            reportProgress(force = true)
            val appContext = context.applicationContext
            CharacterRepository.initialize(appContext)
            val database = EtaDatabase.get(appContext)
            val settings = SettingsDataStore.settings()
            val catalogRevision = SettingsDataStore.officialModelCatalogRevision()
            val summary = EtaBackupJsonStreams.export(appContext, output, onPhase = { phase = it }) { writer ->
                database.withTransaction {
                    val dao = database.conversationDao()
                    val providers = if (EtaBackupContent.MODELS in contents) database.providerDao().providers().map {
                        EtaBackupProvider(it.provider.withoutOAuthSecrets(), it.models)
                    } else emptyList()
                    records += providers.size + providers.sumOf { it.models.size }
                    reportProgress(force = true)
                    val memory = if (EtaBackupContent.MEMORY in contents) AgentMemoryRepository.snapshot().content else ""
                    val memoryScopes = if (EtaBackupContent.MEMORY in contents) AgentMemoryRepository.listScopes().mapNotNull { scope ->
                        if (scope.type == AgentMemoryScopeType.GLOBAL) return@mapNotNull null
                        EtaBackupMemoryScope(scope.type.path, scope.id.orEmpty(), AgentMemoryRepository.snapshot(scope).content)
                    } else emptyList()
                    writer.value("format", String.serializer(), EtaBackupDocument.FORMAT)
                    writer.value("schemaVersion", Int.serializer(), EtaBackupDocument.SCHEMA_VERSION)
                    writer.value("exportedAt", Long.serializer(), System.currentTimeMillis())
                    writer.value("includedContents", kotlinx.serialization.builtins.SetSerializer(EtaBackupContent.serializer()), contents)
                    writer.value("providers", ListSerializer(EtaBackupProvider.serializer()), providers)
                    writer.value("catalogRevision", Int.serializer(), catalogRevision)
                    writer.value("selectedProviderId", String.serializer().nullable, settings.selectedProviderId.takeIf { EtaBackupContent.MODELS in contents })
                    writer.value("selectedModelId", String.serializer().nullable, settings.selectedModelId.takeIf { EtaBackupContent.MODELS in contents })
                    writer.value("conversationState", ConversationStateEntity.serializer().nullable, if (EtaBackupContent.CHATS in contents) dao.state() else null)
                    writer.value("memoryMd", String.serializer(), memory)
                    writer.value("memoryScopes", ListSerializer(EtaBackupMemoryScope.serializer()), memoryScopes)
                    writer.value("memoryEntries", ListSerializer(MemoryEntry.serializer()), if (EtaBackupContent.MEMORY in contents) LearningDatabase.get(appContext).memoryDao().entries() else emptyList())
                    val boundAvatars = linkedMapOf<String, String?>()
                    var conversationCount = 0
                    var messageCount = 0
                    if (EtaBackupContent.CHATS in contents) {
                    phase = "export_conversations"
                    writer.beginArray(EtaBackupJsonStreams.CONVERSATIONS)
                    exportRows({ dao.conversationEntityRowsPage(EXPORT_PAGE_SIZE, it) }) { raw ->
                        val row = dao.restoreConversation(raw)
                        writer.record(ConversationEntity.serializer(), row)
                        CharacterBackupTransfer.avatarReference(row)?.let { (id, avatar) ->
                            if (id !in boundAvatars) boundAvatars[id] = avatar
                        }
                        conversationCount++
                        records++
                        reportProgress()
                    }
                    writer.endArray()
                    reportProgress(force = true)
                    phase = "export_messages"
                    writer.beginArray(EtaBackupJsonStreams.MESSAGES)
                    exportRows({ dao.allMessageRowsPage(EXPORT_PAGE_SIZE, it) }) { raw ->
                        writer.record(ConversationMessageEntity.serializer(), dao.restoreMessage(raw))
                        messageCount++
                        records++
                        reportProgress()
                    }
                    writer.endArray()
                    reportProgress(force = true)
                    phase = "export_checkpoints"
                    writer.beginArray(EtaBackupJsonStreams.CHECKPOINTS)
                    exportRows({ dao.contextCheckpointRowsPage(EXPORT_PAGE_SIZE, it) }) { raw ->
                        writer.record(ConversationContextCheckpointEntity.serializer(), dao.restoreCheckpoint(raw))
                        records++
                        reportProgress()
                    }
                    writer.endArray()
                    phase = "export_roleplay"
                    writer.beginArray(EtaBackupJsonStreams.BRANCHES)
                    exportRows({ dao.branchesPage(EXPORT_PAGE_SIZE, it) }) {
                        writer.record(ConversationBranchEntity.serializer(), it)
                        records++
                        reportProgress()
                    }
                    writer.endArray()
                    writer.beginArray(EtaBackupJsonStreams.ORIGINS)
                    exportRows({ dao.messageOriginsPage(EXPORT_PAGE_SIZE, it) }) {
                        writer.record(ConversationMessageOriginEntity.serializer(), it)
                        records++
                        reportProgress()
                    }
                    writer.endArray()
                    writer.beginArray(EtaBackupJsonStreams.SETUPS)
                    exportRows({ dao.branchSetupsPage(EXPORT_PAGE_SIZE, it) }) {
                        writer.record(ConversationBranchSetupEntity.serializer(), it)
                        records++
                        reportProgress()
                    }
                    writer.endArray()
                    }
                    val roleplay = if (EtaBackupContent.CHATS in contents) CharacterBackupTransfer.snapshot(appContext, boundAvatars) else null
                    writer.value("roleplay", CharacterBackupData.serializer().nullable, roleplay)
                    EtaBackupSummary(providers.size, providers.sumOf { it.models.size }, conversationCount,
                        messageCount, memory.toByteArray(Charsets.UTF_8).size + memoryScopes.sumOf { it.content.toByteArray(Charsets.UTF_8).size }, roleplay?.characters?.size ?: 0,
                        includedContents = contents)
                }
            }
            phase = "export_complete"
            reportProgress(force = true)
            summary
        } catch (failure: Throwable) {
            logFailure("export", phase, failure)
            throw failure
        }
    }

    suspend fun import(
        context: Context,
        input: InputStream,
        onCommitted: () -> Unit = {},
        forgetCredentials: (String) -> Unit = ProviderCredentialRepository::forget,
        onPhase: (String) -> Unit = {},
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        var phase: String by kotlin.properties.Delegates.observable("stage_import") { _, _, value -> onPhase(value) }
        onPhase(phase)
        try {
            val appContext = context.applicationContext
            EtaBackupJsonStreams.stage(appContext, input) { phase = it }.use { staged ->
                phase = "validate_import"
                val summary = EtaBackupValidation.validate(staged)
                val document = staged.header
                val restoreModels = EtaBackupContent.MODELS in document.includedContents
                val restoreChats = EtaBackupContent.CHATS in document.includedContents
                val restoreMemory = EtaBackupContent.MEMORY in document.includedContents
                val database = EtaDatabase.get(appContext)
                val previousMemory = AgentMemoryRepository.snapshot().content
                val memoryDatabase = LearningDatabase.get(appContext)
                val previousEntries = memoryDatabase.memoryDao().entries()
                val previousMemoryJobs = memoryDatabase.memoryDao().jobs()
                val previousMemoryScopes = AgentMemoryRepository.listScopes().associate { scope ->
                    scope.key to (scope to AgentMemoryRepository.snapshot(scope).content)
                }
                val previousRoleMemories = document.roleplay?.assets.orEmpty().associate {
                    it.characterId to CharacterMemoryRepository.snapshot(appContext, it.characterId).content
                }
                phase = "restore_assets"
                val avatarPaths = document.roleplay?.let { CharacterBackupTransfer.restoreAssets(appContext, it) }.orEmpty()
                val cancellation = currentCoroutineContext()
                val oauthProviders = document.providers.map { it.provider }
                    .filter { ProviderAuthModes.isOAuth(it.authMode) }
                val credentialIds = document.providers.mapTo(linkedSetOf()) { it.provider.id }
                // 显式检查原任务取消，使 Room 提交到凭据清理之间不留下被取消打断的空隙。
                withContext(NonCancellable) {
                    var memoryWriteStarted = false
                    var roleMemoryWriteStarted = false
                    try {
                        database.withTransaction {
                            cancellation.ensureActive()
                            phase = "restore_providers"
                            if (restoreModels) {
                            credentialIds += database.providerDao().providers().map { it.provider.id }
                            database.providerDao().replaceAll(document.providers.map {
                                ProviderWithModelsSeed(it.provider.awaitingCredentialCleanup(), it.models)
                            })
                            }
                            val dao = database.conversationDao()
                            if (restoreChats) {
                            phase = "clear_conversations"
                            dao.deleteMessages()
                            dao.deleteContextCheckpoints()
                            dao.deleteConversations()
                            dao.deleteState()
                            phase = "restore_conversations"
                            staged.conversations { row ->
                                cancellation.ensureActive()
                                dao.insertConversations(listOf(CharacterBackupTransfer.remapConversation(row, avatarPaths)))
                            }
                            phase = "restore_messages"
                            staged.messages {
                                cancellation.ensureActive()
                                dao.insertMessages(listOf(it))
                            }
                            phase = "restore_checkpoints"
                            staged.checkpoints {
                                cancellation.ensureActive()
                                dao.insertContextCheckpoints(listOf(it))
                            }
                            document.conversationState?.let { dao.insertState(it) }
                            phase = "restore_branches"
                            staged.branches { cancellation.ensureActive(); dao.insertBranch(it) }
                            staged.origins { cancellation.ensureActive(); dao.insertMessageOrigin(it) }
                            staged.setups {
                                cancellation.ensureActive()
                                // 换代次以免复用目标设备旧回执；已经放弃的继承不能因导入重新启用。
                                val abandoned = it.completionMode == "current_settings"
                                dao.insertBranchSetup(it.copy(setupId = java.util.UUID.randomUUID().toString(),
                                    completedAt = it.completedAt.takeIf { abandoned },
                                    completionMode = it.completionMode.takeIf { abandoned }, errorCode = null))
                            }
                            phase = "restore_roleplay"
                            document.roleplay?.let { roleplay ->
                                database.characterDao().replaceAll(
                                    roleplay.characters.map { it.copy(avatarPath = avatarPaths[it.id]) }, roleplay.persona,
                                )
                            }
                            }
                            phase = "restore_memory"
                            cancellation.ensureActive()
                            if (restoreMemory) {
                            memoryWriteStarted = true
                            AgentMemoryRepository.replaceAll(document.memoryMd)
                            AgentMemoryRepository.clearScopedScopes()
                            document.memoryScopes.forEach { scoped ->
                                val scope = AgentMemoryScope.fromPath(scoped.type, scoped.id)
                                    ?: throw EtaBackupException("备份中的记忆作用域无效")
                                AgentMemoryRepository.replaceAll(scope, scoped.content)
                            }
                            memoryDatabase.withTransaction {
                                memoryDatabase.memoryDao().clearEntries()
                                memoryDatabase.memoryDao().clearJobs()
                                document.memoryEntries.forEach { memoryDatabase.memoryDao().insert(it) }
                            }
                            }
                            if (restoreChats) document.roleplay?.let {
                                roleMemoryWriteStarted = true
                                CharacterBackupTransfer.restoreMemories(appContext, it)
                            }
                            cancellation.ensureActive()
                        }
                    } catch (failure: Throwable) {
                        if (memoryWriteStarted) {
                            try { memoryDatabase.withTransaction {
                                memoryDatabase.memoryDao().clearEntries()
                                memoryDatabase.memoryDao().clearJobs()
                                previousEntries.forEach { memoryDatabase.memoryDao().insert(it) }
                                previousMemoryJobs.forEach { memoryDatabase.memoryDao().insertJob(it) }
                            } } catch (restoreFailure: Throwable) { failure.addSuppressed(restoreFailure) }
                            try { AgentMemoryRepository.replaceAll(previousMemory) } catch (restoreFailure: Throwable) {
                                failure.addSuppressed(restoreFailure)
                            }
                            try { AgentMemoryRepository.clearScopedScopes() } catch (restoreFailure: Throwable) { failure.addSuppressed(restoreFailure) }
                            previousMemoryScopes.values.forEach { (scope, content) ->
                                try { AgentMemoryRepository.replaceAll(scope, content) } catch (restoreFailure: Throwable) {
                                    failure.addSuppressed(restoreFailure)
                                }
                            }
                        }
                        if (roleMemoryWriteStarted) {
                            previousRoleMemories.forEach { (id, content) ->
                                try { CharacterMemoryRepository.replaceAll(appContext, id, content) } catch (restoreFailure: Throwable) {
                                    failure.addSuppressed(restoreFailure)
                                }
                            }
                        }
                        CharacterBackupTransfer.discardAssets(avatarPaths, failure)
                        throw failure
                    }
                    onCommitted()
                    try {
                        phase = "clear_local_drafts"
                        // Logical JSON does not contain UI drafts. Never attach old local input to
                        // an imported identity; complete private archives restore their own prefs.
                        if (restoreChats) {
                            ConversationDraftRepository.get(appContext).clearAll().await()
                            ConversationAttachmentDrafts.get(appContext).clearAll().await()
                        }
                    } catch (failure: Exception) {
                        throw IllegalStateException("数据已导入，但本地草稿清理未完成，请重启后检查草稿。", failure)
                    }
                    try {
                        phase = "clear_provider_credentials"
                        if (restoreModels) credentialIds.forEach(forgetCredentials)
                        phase = "activate_provider_connections"
                        database.withTransaction {
                            oauthProviders.forEach { provider ->
                                check(database.providerDao().activateRestoredOAuthProvider(
                                    provider.id, provider.authMode, provider.isEnabled,
                                ) == 1)
                            }
                        }
                    } catch (failure: Exception) {
                        throw IllegalStateException(
                            "数据已导入，但订阅登录清理未完成。订阅连接保持停用，请重新选择登录方式并登录。",
                            failure,
                        )
                    }
                    try {
                        phase = "restore_settings"
                        if (restoreModels) {
                            SettingsDataStore.setSelection(document.selectedProviderId, document.selectedModelId)
                            SettingsDataStore.setOfficialModelCatalogRevision(document.catalogRevision)
                            ProviderRepository.ensureBuiltInsMerged()
                            ProviderRepository.repairSelection()
                        }
                    } catch (failure: Exception) {
                        throw IllegalStateException("数据已导入，但默认模型设置未完全恢复，请重新选择服务商和模型。", failure)
                    }
                }
                summary
            }
        } catch (failure: Throwable) {
            logFailure("import", phase, failure)
            throw failure
        }
    }

    suspend fun inspect(context: Context, input: InputStream): EtaBackupSummary = withContext(Dispatchers.IO) {
        EtaBackupJsonStreams.stage(context.applicationContext, input).use { EtaBackupValidation.validate(it) }
    }

    private suspend fun <T> exportRows(load: suspend (Int) -> List<T>, write: suspend (T) -> Unit) {
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = load(offset)
            for (row in page) {
                currentCoroutineContext().ensureActive()
                write(row)
            }
            if (page.size < EXPORT_PAGE_SIZE) return
            offset += page.size
        }
    }

    private fun ProviderEntity.withoutOAuthSecrets(): ProviderEntity =
        if (authMode == ProviderAuthModes.API_KEY) this else copy(apiKey = "")

    private fun ProviderEntity.awaitingCredentialCleanup(): ProviderEntity =
        if (ProviderAuthModes.isOAuth(authMode)) {
            // 即使提交后进程中断，导入的相同 ID 也不能接上设备中尚未清除的旧授权。
            copy(authMode = ProviderAuthModes.API_KEY, apiKey = "", isEnabled = false)
        } else withoutOAuthSecrets()

    private fun logFailure(operation: String, phase: String, failure: Throwable) {
        if (failure is CancellationException) return
        AndroidAgentLogger.error("Agent backup failed: operation=$operation phase=$phase " +
            "type=${failure.safeLogType()}\n${failure.safeStackTrace()}")
    }
}
