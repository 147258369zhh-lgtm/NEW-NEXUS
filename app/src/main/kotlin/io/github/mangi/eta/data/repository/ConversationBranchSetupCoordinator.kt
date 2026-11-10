package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.data.db.ConversationBranchSetupEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.fork.conversations.ConversationExtensionDatabase
import io.github.mangi.eta.fork.conversations.ConversationExtensionRepository
import io.github.mangi.eta.fork.projects.ProjectDatabase
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.mangi.eta.fork.projects.ProjectSubjectType
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 核心事务固定配置快照，目标库事务同时写回执；崩溃/取消后补做剩余阶段，不重置已生效设置。 */
internal object ConversationBranchSetupCoordinator {
    private val mutex = Mutex()

    suspend fun capture(context: Context, sourceId: String, childId: String, now: Long): ConversationBranchSetupEntity = withContext(Dispatchers.IO) {
        val extension = ConversationExtensionDatabase.get(context).conversationExtensionDao().extension(sourceId)
        val project = ProjectRepository.bindingForSubject(context, ProjectSubjectType.CONVERSATION, sourceId)
        ConversationBranchSetupEntity(childId, UUID.randomUUID().toString(), extension?.folderId,
            extension?.contextWindowOverride, project?.projectId, now)
    }

    suspend fun ensure(context: Context, id: String, useCurrent: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            var db: EtaDatabase? = null
            var setup: ConversationBranchSetupEntity? = null
            var stage = "SETUP_STORAGE_UNAVAILABLE"
            try {
                val database = EtaDatabase.get(context)
                db = database
                database.withTransaction {
                    val dao = database.conversationDao()
                    val row = dao.branchSetup(id) ?: return@withTransaction
                    setup = row
                    if (row.completedAt != null) return@withTransaction
                    // 保持核心事务直到回执确认，删除/导入必须等候；目标库各自幂等提交。
                    stage = "FOLDER_UNAVAILABLE"
                    ConversationExtensionRepository.initializeBranch(context, row, useCurrent)
                    stage = "PROJECT_UNAVAILABLE"
                    ProjectRepository.initializeBranch(context, row, useCurrent)
                    stage = "SETUP_STORAGE_UNAVAILABLE"
                    currentCoroutineContext().ensureActive()
                    dao.completeBranchSetup(id, row.setupId, maxOf(System.currentTimeMillis(), row.createdAt),
                        if (useCurrent) "current_settings" else "inherited")
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 不把 SQL/路径/服务异常正文写进持久记录，只保存稳定且可翻译的阶段码。
                setup?.let { row -> withContext(NonCancellable) {
                    runCatching { db?.conversationDao()?.branchSetupError(id, row.setupId, stage) }
                } }
                false
            }
        }
    }

    suspend fun recoverPending(context: Context) = withContext(Dispatchers.IO) {
        // 每次启动仅尝试一次各条记录；失败留给可见重试或执行前检查，不无限后台循环。
        val ids = mutableListOf<String>()
        val dao = EtaDatabase.get(context).conversationDao()
        var offset = 0
        while (true) {
            val rows = dao.branchSetupsPage(128, offset)
            ids += rows.filter { it.completedAt == null }.map { it.conversationId }
            if (rows.size < 128) break
            offset += rows.size
        }
        for (id in ids) { currentCoroutineContext().ensureActive(); ensure(context, id) }
    }

    suspend fun cleanDeleted(context: Context, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            EtaDatabase.get(context).withTransaction {
                if (EtaDatabase.get(context).conversationDao().conversationExists(id)) return@withTransaction
                val extensions = ConversationExtensionDatabase.get(context)
                extensions.withTransaction {
                    val dao = extensions.conversationExtensionDao()
                    dao.deleteArchiveEvents(id); dao.deleteExtension(id); dao.deleteBranchSetupReceipts(id)
                }
                val projects = ProjectDatabase.get(context)
                projects.withTransaction {
                    projects.projectDao().deleteBinding(ProjectSubjectType.CONVERSATION, id)
                    projects.projectDao().deleteBranchSetupReceipts(id)
                }
            }
        }
    }
}
