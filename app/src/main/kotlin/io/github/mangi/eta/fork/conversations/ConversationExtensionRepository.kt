package io.github.mangi.eta.fork.conversations

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

internal object ConversationExtensionRepository {
    suspend fun initializeBranch(
        context: Context, setup: io.github.mangi.eta.data.db.ConversationBranchSetupEntity, useCurrent: Boolean,
    ) {
        val db = ConversationExtensionDatabase.get(context)
        db.withTransaction {
            val dao = db.conversationExtensionDao()
            if (dao.hasBranchSetupReceipt(setup.setupId)) return@withTransaction
            if (!useCurrent) {
                check(setup.folderId == null || dao.folder(setup.folderId) != null) { "FOLDER_UNAVAILABLE" }
                val current = dao.extension(setup.conversationId) ?: ConversationExtensionEntity(
                    conversationId = setup.conversationId, lastActiveAt = setup.createdAt,
                    createdAt = setup.createdAt, updatedAt = setup.createdAt)
                dao.upsertExtension(current.copy(folderId = setup.folderId,
                    folderSortOrder = setup.folderId?.let { dao.maxFolderConversationSortOrder(it) + 1 } ?: 0,
                    contextWindowOverride = setup.contextWindow))
            }
            dao.insertBranchSetupReceipt(io.github.mangi.eta.data.db.BranchSetupReceiptEntity(setup.setupId, setup.conversationId, setup.createdAt))
        }
    }

    fun snapshot(context: Context): Flow<ConversationExtensionSnapshot> {
        val dao = ConversationExtensionDatabase.get(context).conversationExtensionDao()
        return combine(
            dao.foldersFlow(),
            dao.extensionsFlow(),
            dao.archiveSettingsFlow(),
        ) { folders, extensions, settings ->
            ConversationExtensionSnapshot(
                folders = ConversationFolderOrdering.sortFolders(folders.map { it.toModel() }),
                conversations = extensions.associate { it.conversationId to it.toModel() },
                archiveSettings = settings?.toModel() ?: ConversationArchiveSettings(),
            )
        }.distinctUntilChanged()
    }

    suspend fun ensureConversations(
        context: Context,
        conversationIds: Collection<String>,
        defaultLastActiveAt: Long = System.currentTimeMillis(),
        lastActiveAt: (String) -> Long? = { null },
    ) {
        val ids = conversationIds.filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return
        ConversationExtensionDatabase.get(context).conversationExtensionDao().insertExtensions(
            ids.map { id ->
                ConversationExtensionEntity(
                    conversationId = id,
                    lastActiveAt = lastActiveAt(id)?.takeIf { it > 0L } ?: defaultLastActiveAt,
                    createdAt = defaultLastActiveAt,
                    updatedAt = defaultLastActiveAt,
                )
            },
        )
    }

    suspend fun touchConversation(
        context: Context,
        conversationId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val dao = ConversationExtensionDatabase.get(context).conversationExtensionDao()
        if (dao.extension(conversationId)?.archivedAt != null) return
        dao.touchLastActive(conversationId, now)
    }

    suspend fun createFolder(
        context: Context,
        name: String,
        now: Long = System.currentTimeMillis(),
        parentId: String? = null,
    ): String {
        val normalized = folderName(name)
        val database = ConversationExtensionDatabase.get(context)
        return database.withTransaction {
            val dao = database.conversationExtensionDao()
            val folders = dao.folders()
            requireParent(folders, parentId)
            if (parentId != null && folderTree(folders).ancestors(parentId).size + 2 > MAX_CONVERSATION_FOLDER_DEPTH) {
                throw ConversationFolderException("DEPTH_LIMIT")
            }
            requireUniqueName(folders, normalized, parentId)
            val id = UUID.randomUUID().toString()
            dao.insertFolder(
                ConversationFolderEntity(
                    id = id,
                    name = normalized,
                    sortOrder = dao.maxFolderSortOrder() + 1,
                    createdAt = now,
                    updatedAt = now,
                    parentId = parentId,
                ),
            )
            id
        }
    }

    suspend fun renameFolder(
        context: Context,
        folder: ConversationFolder,
        name: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val normalized = folderName(name)
        val database = ConversationExtensionDatabase.get(context)
        database.withTransaction {
            val dao = database.conversationExtensionDao()
            val current = dao.folder(folder.id) ?: throw ConversationFolderException("NOT_FOUND")
            requireUniqueName(dao.folders(), normalized, current.parentId, current.id)
            dao.updateFolderEntity(current.copy(name = normalized, updatedAt = now))
        }
    }

    suspend fun moveFolder(context: Context, folderId: String, parentId: String?, now: Long = System.currentTimeMillis()) {
        val database = ConversationExtensionDatabase.get(context)
        database.withTransaction {
            val dao = database.conversationExtensionDao()
            val current = dao.folder(folderId) ?: throw ConversationFolderException("NOT_FOUND")
            val folders = dao.folders()
            requireParent(folders, parentId)
            val tree = folderTree(folders)
            if (parentId in tree.subtreeIds(folderId)) throw ConversationFolderException("CYCLE")
            if (!tree.canMove(folderId, parentId)) throw ConversationFolderException("DEPTH_LIMIT")
            requireUniqueName(folders, current.name, parentId, folderId)
            if (current.parentId != parentId) {
                dao.updateFolderEntity(current.copy(parentId = parentId, sortOrder = dao.maxFolderSortOrder() + 1, updatedAt = now))
            }
        }
    }

    suspend fun deleteFolder(
        context: Context,
        folderId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = ConversationExtensionDatabase.get(context)
        database.withTransaction {
            val dao = database.conversationExtensionDao()
            val current = dao.folder(folderId) ?: throw ConversationFolderException("NOT_FOUND")
            val folders = dao.folders()
            requireParent(folders, current.parentId)
            val children = folders.filter { it.parentId == folderId }
            val remaining = folders.filterNot { it.id == folderId }
            children.forEach { requireUniqueName(remaining, it.name, current.parentId, it.id) }
            var order = dao.maxFolderSortOrder()
            var memberOrder = current.parentId?.let { dao.maxFolderConversationSortOrder(it) } ?: -1
            dao.deleteFolder(folderId)
            children.forEach { dao.updateFolderEntity(it.copy(parentId = current.parentId, sortOrder = ++order, updatedAt = now)) }
            dao.folderMembers(folderId).forEach {
                dao.updateFolder(it.conversationId, current.parentId, ++memberOrder, now)
            }
        }
    }

    suspend fun moveConversation(
        context: Context,
        conversationId: String,
        folderId: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = ConversationExtensionDatabase.get(context)
        database.withTransaction {
            val dao = database.conversationExtensionDao()
            requireParent(dao.folders(), folderId)
            if (dao.extension(conversationId) == null) throw ConversationFolderException("CONVERSATION_NOT_FOUND")
            val sortOrder = folderId?.let { dao.maxFolderConversationSortOrder(it) + 1 } ?: 0L
            dao.updateFolder(conversationId, folderId, sortOrder, now)
        }
    }

    private fun folderName(name: String): String = name.trim().also {
        if (it.isEmpty() || it.length > 80 || it.any { char -> char.isISOControl() || char == '/' || char == '\\' }) {
            throw ConversationFolderException("INVALID_NAME")
        }
    }

    private fun requireParent(folders: List<ConversationFolderEntity>, parentId: String?) {
        if (parentId != null && folders.none { it.id == parentId }) throw ConversationFolderException("NOT_FOUND")
    }

    private fun requireUniqueName(folders: List<ConversationFolderEntity>, name: String, parentId: String?, except: String? = null) {
        if (folders.any { it.id != except && it.parentId == parentId && it.name == name }) throw ConversationFolderException("DUPLICATE_NAME")
    }

    private fun folderTree(folders: List<ConversationFolderEntity>) = ConversationFolderTree(folders, { it.id }, { it.parentId })

    suspend fun archiveConversation(
        context: Context,
        conversationId: String,
        reason: String = MANUAL_ARCHIVE_REASON,
        isAutomatic: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = ConversationExtensionDatabase.get(context)
        database.withTransaction {
            val dao = database.conversationExtensionDao()
            dao.markArchived(conversationId, now, reason, now)
            dao.insertArchiveEvent(
                ConversationArchiveEventEntity(
                    conversationId = conversationId,
                    type = "archived",
                    reason = reason,
                    isAutomatic = isAutomatic,
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun restoreConversation(
        context: Context,
        conversationId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = ConversationExtensionDatabase.get(context)
        database.withTransaction {
            val dao = database.conversationExtensionDao()
            dao.markRestored(conversationId, now)
            dao.insertArchiveEvent(
                ConversationArchiveEventEntity(
                    conversationId = conversationId,
                    type = "restored",
                    reason = MANUAL_ARCHIVE_REASON,
                    isAutomatic = false,
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun setAutoArchiveExempt(
        context: Context,
        conversationId: String,
        exempt: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        ConversationExtensionDatabase.get(context).conversationExtensionDao()
            .setArchiveExempt(conversationId, exempt, now)
    }

    suspend fun setKind(
        context: Context,
        conversationId: String,
        kind: String,
        now: Long = System.currentTimeMillis(),
    ) {
        ConversationExtensionDatabase.get(context).conversationExtensionDao()
            .setKind(conversationId, kind, now)
    }

    suspend fun contextWindowOverride(context: Context, conversationId: String): Int? =
        ConversationExtensionDatabase.get(context).conversationExtensionDao()
            .extension(conversationId)?.contextWindowOverride

    suspend fun setContextWindowOverride(
        context: Context,
        conversationId: String,
        contextWindow: Int?,
        now: Long = System.currentTimeMillis(),
    ) {
        require(contextWindow == null || contextWindow in 1_024..16_000_000) {
            "CONTEXT_WINDOW_INVALID"
        }
        val updated = ConversationExtensionDatabase.get(context).conversationExtensionDao()
            .setContextWindowOverride(conversationId, contextWindow, now)
        if (updated == 0) throw ConversationFolderException("CONVERSATION_NOT_FOUND")
    }

    suspend fun archiveSettings(context: Context): ConversationArchiveSettings =
        ConversationExtensionDatabase.get(context).conversationExtensionDao()
            .archiveSettings()?.toModel() ?: ConversationArchiveSettings()

    suspend fun updateArchiveSettings(
        context: Context,
        transform: (ConversationArchiveSettings) -> ConversationArchiveSettings,
    ): ConversationArchiveSettings {
        val database = ConversationExtensionDatabase.get(context)
        return database.withTransaction {
            val dao = database.conversationExtensionDao()
            val next = transform(dao.archiveSettings()?.toModel() ?: ConversationArchiveSettings())
            dao.upsertArchiveSettings(next.toEntity())
            next
        }
    }

    suspend fun archiveEligibleConversations(
        context: Context,
        eligibility: (String) -> ConversationArchiveEligibility,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        val database = ConversationExtensionDatabase.get(context)
        val settings = database.conversationExtensionDao().archiveSettings()?.toModel()
            ?: ConversationArchiveSettings()
        val eligible = ConversationArchivePolicy.eligibleConversationIds(
            extensions = database.conversationExtensionDao().extensions().map { it.toModel() },
            settings = settings,
            nowMillis = now,
            eligibility = eligibility,
        )
        eligible.forEach { id ->
            archiveConversation(context, id, IDLE_ARCHIVE_REASON, isAutomatic = true, now = now)
        }
        return eligible
    }

    suspend fun deleteConversation(
        context: Context,
        conversationId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = ConversationExtensionDatabase.get(context)
        database.withTransaction {
            val dao = database.conversationExtensionDao()
            dao.deleteArchiveEvents(conversationId)
            dao.deleteExtension(conversationId)
        }
    }

    suspend fun recordArchiveCheck(
        context: Context,
        checkedAt: Long,
        error: String?,
    ) {
        updateArchiveSettings(context) { settings ->
            settings.copy(lastCheckedAt = checkedAt, lastError = error)
        }
    }
}
