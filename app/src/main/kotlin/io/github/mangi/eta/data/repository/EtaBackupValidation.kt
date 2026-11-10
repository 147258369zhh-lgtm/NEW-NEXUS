package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.model.ProviderAuthModes

/** 校验只保留标识与角色关联所需的消息类型，正文和检查点逐条读取后释放。 */
internal object EtaBackupValidation {
    suspend fun validate(staged: EtaBackupJsonStreams.StagedDocument): EtaBackupSummary {
        val header = staged.header
        validateHeader(header)
        val conversationIds = mutableSetOf<String>()
        val revisionConversationIds = mutableSetOf<String>()
        val bindingIds = mutableSetOf<String>()
        try {
            staged.conversations { row ->
                if (EtaBackupContent.CHATS !in header.includedContents) throw EtaBackupException("备份内容与清单不一致")
                if (row.id.isBlank() || !conversationIds.add(row.id)) {
                    throw EtaBackupException("备份中的会话存在重复或无效 ID")
                }
                if (row.revisionsJson.isNotBlank()) revisionConversationIds += row.id
                CharacterBackupTransfer.validateBindings(listOf(row)).forEach { bindingIds += it.characterId }
            }
            if (header.conversationState != null && header.conversationState.selectedConversationId !in conversationIds) {
                throw EtaBackupException("备份中的当前会话不存在")
            }
            val messageIds = mutableMapOf<String, String>()
            val messagePositions = mutableSetOf<Pair<String, Int>>()
            val revisionMessageTypes = mutableMapOf<String, MutableMap<String, String>>()
            var messageCount = 0
            staged.messages { row ->
                if (row.conversationId !in conversationIds) throw EtaBackupException("备份中的消息缺少所属会话")
                if (row.id.isBlank() || messageIds.put(row.id, row.conversationId) != null) throw EtaBackupException("备份中的消息 ID 重复")
                if (!messagePositions.add(row.conversationId to row.sortIndex)) throw EtaBackupException("备份中的消息顺序重复")
                if (row.conversationId in revisionConversationIds) {
                    revisionMessageTypes.getOrPut(row.conversationId) { mutableMapOf() }[row.id] = row.type
                }
                messageCount++
            }
            staged.conversations { row ->
                CharacterBackupTransfer.validateRevisions(row, revisionMessageTypes[row.id].orEmpty())
            }
            val checkpointIds = mutableSetOf<String>()
            staged.checkpoints { row ->
                if (row.conversationId !in conversationIds) throw EtaBackupException("备份中的上下文检查点缺少所属会话")
                if (!checkpointIds.add(row.conversationId)) throw EtaBackupException("备份中的上下文检查点重复")
            }
            header.roleplay?.let { CharacterBackupTransfer.validate(it, bindingIds) }
            val branches = mutableMapOf<String, io.github.mangi.eta.data.db.ConversationBranchEntity>()
            staged.branches { row ->
                if (row.conversationId !in conversationIds || row.familyId.isBlank() || branches.put(row.conversationId, row) != null ||
                    row.historyMode !in setOf("journal", "visible_reconstruction") || row.createdAt < 0 ||
                    (row.sourceConversationId == null) != (row.sourceMessageId == null) ||
                    (row.sourceMessageId == null) != (row.sourceSortIndex == null) ||
                    row.sourceConversationId?.isBlank() == true || row.sourceMessageId?.isBlank() == true ||
                    (row.sourceSortIndex ?: 0) < 0 ||
                    (row.parentConversationId != null && row.parentConversationId != row.sourceConversationId)) {
                    throw EtaBackupException("备份中的分支记录无效")
                }
            }
            val visited = mutableSetOf<String>()
            branches.values.forEach { node ->
                var current: io.github.mangi.eta.data.db.ConversationBranchEntity? = node
                val path = mutableSetOf<String>()
                while (current != null && current.conversationId !in visited) {
                    if (!path.add(current.conversationId)) throw EtaBackupException("备份中的分支关系存在循环")
                    val parent = current.parentConversationId
                    current = parent?.let { branches[it] ?: throw EtaBackupException("备份中的分支缺少父节点") }
                    if (current != null && current.familyId != node.familyId) throw EtaBackupException("备份中的分支家族不一致")
                }
                visited += path
            }
            val originIds = mutableSetOf<String>()
            staged.origins { row ->
                if (!originIds.add(row.messageId) || messageIds[row.messageId] != row.conversationId ||
                    row.conversationId !in branches || row.sourceConversationId.isBlank() || row.sourceMessageId.isBlank() || row.sourceSortIndex < 0) {
                    throw EtaBackupException("备份中的消息来源无效")
                }
            }
            val setupConversationIds = mutableSetOf<String>()
            val setupIds = mutableSetOf<String>()
            staged.setups { row ->
                if (branches[row.conversationId]?.sourceConversationId == null || !setupConversationIds.add(row.conversationId) ||
                    row.setupId.isBlank() || row.setupId.length > 128 || !setupIds.add(row.setupId) ||
                    row.folderId?.let { it.isBlank() || it.length > 288 } == true ||
                    row.projectId?.let { it.isBlank() || it.length > 288 } == true ||
                    row.contextWindow?.let { it !in 1_024..16_000_000 } == true || row.createdAt < 0 ||
                    row.completedAt?.let { it < row.createdAt } == true ||
                    (row.completedAt == null) != (row.completionMode == null) ||
                    row.completionMode?.let { it !in setOf("inherited", "current_settings") } == true ||
                    row.errorCode?.let { it !in setOf("FOLDER_UNAVAILABLE", "PROJECT_UNAVAILABLE", "SETUP_STORAGE_UNAVAILABLE") } == true ||
                    (row.completedAt != null && row.errorCode != null)) {
                    throw EtaBackupException("备份中的分支配置快照无效")
                }
            }
            return EtaBackupSummary(
                providerCount = header.providers.size,
                modelCount = header.providers.sumOf { it.models.size },
                conversationCount = conversationIds.size,
                messageCount = messageCount,
                memoryBytes = header.memoryMd.toByteArray(Charsets.UTF_8).size,
                characterCount = header.roleplay?.characters?.size ?: 0,
                includedContents = header.includedContents,
            )
        } catch (failure: IllegalArgumentException) {
            if (failure is EtaBackupException) throw failure
            throw EtaBackupException("备份中的记录或角色数据无效", failure)
        }
    }

    private fun validateHeader(document: EtaBackupDocument) {
        if (document.includedContents.isEmpty() || document.schemaVersion < 8 && document.includedContents != EtaBackupContent.entries.toSet()) {
            throw EtaBackupException("备份内容选择无效")
        }
        if (EtaBackupContent.MODELS !in document.includedContents && (document.providers.isNotEmpty() || document.selectedModelId != null || document.selectedProviderId != null) ||
            EtaBackupContent.MEMORY !in document.includedContents && (document.memoryMd.isNotEmpty() || document.memoryScopes.isNotEmpty() || document.memoryEntries.isNotEmpty()) ||
            EtaBackupContent.CHATS !in document.includedContents && (document.roleplay != null || document.conversationState != null)) {
            throw EtaBackupException("备份内容与清单不一致")
        }
        if (document.format != EtaBackupDocument.FORMAT) throw EtaBackupException("这不是 Nexus 备份文件")
        if (document.schemaVersion !in 1..EtaBackupDocument.SCHEMA_VERSION) {
            throw EtaBackupException("不支持的 Nexus 备份版本：${document.schemaVersion}")
        }
        if (document.catalogRevision < 0) throw EtaBackupException("备份中的模型目录版本无效")
        val providerIds = document.providers.map { it.provider.id }
        if (providerIds.size != providerIds.toSet().size || providerIds.any(String::isBlank)) {
            throw EtaBackupException("备份中的模型提供商存在重复或无效 ID")
        }
        val modelIds = document.providers.flatMap { provider ->
            if (provider.provider.authMode != ProviderAuthModes.API_KEY &&
                !ProviderAuthModes.isOAuth(provider.provider.authMode)
            ) throw EtaBackupException("备份中的服务商认证方式不受支持")
            val ids = provider.models.map { it.id }
            if (ids.size != ids.toSet().size || ids.any(String::isBlank)) throw EtaBackupException("备份中的模型存在重复或无效 ID")
            if (provider.models.any { it.providerId != provider.provider.id }) throw EtaBackupException("备份中的模型与提供商不匹配")
            provider.models.map { it.id to provider.provider.id }
        }
        if (modelIds.size != modelIds.map { it.first }.toSet().size) throw EtaBackupException("备份中的模型 ID 重复")
        if (document.selectedProviderId != null && document.selectedProviderId !in providerIds) {
            throw EtaBackupException("备份中的当前提供商不存在")
        }
        val selectedModel = document.selectedModelId?.let { id -> modelIds.firstOrNull { it.first == id } }
        if (document.selectedModelId != null && selectedModel == null) throw EtaBackupException("备份中的当前模型不存在")
        if (selectedModel != null && selectedModel.second != document.selectedProviderId) {
            throw EtaBackupException("备份中的当前模型与提供商不匹配")
        }
        if (document.conversationState != null && document.conversationState.id != ConversationStateEntity.SINGLETON_ID) {
            throw EtaBackupException("备份中的会话状态无效")
        }
        if (document.memoryMd.toByteArray(Charsets.UTF_8).size > 1024 * 1024) throw EtaBackupException("MEMORY.md 超过 1 MiB 限制")
        val scopeKeys = mutableSetOf<String>()
        document.memoryScopes.forEach { scope ->
            val parsed = AgentMemoryScope.fromPath(scope.type, scope.id)
                ?: throw EtaBackupException("备份中的记忆作用域无效")
            if (!scopeKeys.add(parsed.key)) throw EtaBackupException("备份中的记忆作用域重复")
            if (scope.content.toByteArray(Charsets.UTF_8).size > AgentMemoryStore.MAX_FILE_BYTES) {
                throw EtaBackupException("备份中的作用域记忆超过 1 MiB 限制")
            }
        }
        if (document.memoryEntries.size > 10000) throw EtaBackupException("记忆条目过多")
        val memoryIds = mutableSetOf<String>()
        document.memoryEntries.forEach { entry ->
            runCatching { io.github.mangi.eta.agent.memory.MemoryAutomationPolicy.parseScope(entry.scope) }
                .getOrElse { throw EtaBackupException("记忆条目作用域无效") }
            if (!memoryIds.add(entry.id) || !entry.id.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
                entry.layer !in setOf("core", "project", "task", "long_term", "working", "other") ||
                entry.status !in setOf(
                    "pending", "butler_review", "butler_approved", "confirmed",
                    "active", "conflict", "rejected", "archived", "superseded",
                ) ||
                entry.topic.isBlank() || entry.topic.length > 100 || entry.content.trim().length !in 4..1200 || entry.evidence.length > 600 ||
                !entry.fingerprint.matches(Regex("[a-f0-9]{64}")) ||
                entry.source.length > 256 || entry.createdAt < 0 || entry.updatedAt < entry.createdAt ||
                !entry.confidence.isFinite() || entry.confidence !in 0.0..1.0 ||
                entry.expiresAt?.let { it < entry.createdAt } == true || entry.conflictIds.length > 16000 || entry.supersedes.length > 16000) {
                throw EtaBackupException("备份中的记忆条目无效")
            }
        }
    }
}
