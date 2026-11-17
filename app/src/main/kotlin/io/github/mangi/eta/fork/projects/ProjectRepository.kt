package io.github.mangi.eta.fork.projects

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object ProjectRepository {
    suspend fun initializeBranch(
        context: Context, setup: io.github.mangi.eta.data.db.ConversationBranchSetupEntity, useCurrent: Boolean,
    ) {
        val db = ProjectDatabase.get(context)
        db.withTransaction {
            val dao = db.projectDao()
            if (dao.hasBranchSetupReceipt(setup.setupId)) return@withTransaction
            if (!useCurrent) {
                val projectId = setup.projectId
                if (projectId == null) dao.deleteBinding(ProjectSubjectType.CONVERSATION, setup.conversationId)
                else {
                    val project = dao.project(projectId)
                    check(project != null && project.archivedAt == null) { "PROJECT_UNAVAILABLE" }
                    dao.upsertBinding(ProjectSubjectBindingEntity(projectId, ProjectSubjectType.CONVERSATION,
                        setup.conversationId, setup.createdAt, setup.createdAt))
                }
            }
            dao.insertBranchSetupReceipt(io.github.mangi.eta.data.db.BranchSetupReceiptEntity(setup.setupId, setup.conversationId, setup.createdAt))
        }
    }

    fun projects(context: Context): Flow<List<ProjectEntity>> =
        ProjectDatabase.get(context).projectDao().projectsFlow()

    fun project(context: Context, projectId: String): Flow<ProjectEntity?> =
        ProjectDatabase.get(context).projectDao().projectFlow(projectId)

    suspend fun create(context: Context, draft: ProjectDraft, now: Long = System.currentTimeMillis()): String {
        val normalized = normalizeDraft(draft)
        if (normalized.source == "SAF") {
            val root = android.net.Uri.parse(normalized.rootIdentityKey)
            val hasReadWriteGrant = context.contentResolver.persistedUriPermissions.any {
                it.uri == root && it.isReadPermission && it.isWritePermission
            }
            try {
                validateSafRoot(root, hasReadWriteGrant)
            } catch (failure: IllegalArgumentException) {
                val code = failure.message?.takeIf { it == "ROOT_PERMISSION_REQUIRED" } ?: "ROOT_INVALID"
                throw ProjectException(code)
            }
        }
        val database = ProjectDatabase.get(context)
        return database.withTransaction {
            val dao = database.projectDao()
            if (dao.projectForRoot(normalized.rootIdentityKey) != null) {
                throw ProjectException("ROOT_ALREADY_REGISTERED")
            }
            val id = "project_${UUID.randomUUID()}"
            dao.insertProject(
                ProjectEntity(
                    id = id,
                    name = normalized.name,
                    providerId = normalized.providerId,
                    rootIdentityKey = normalized.rootIdentityKey,
                    rootDisplayName = normalized.rootDisplayName,
                    source = normalized.source,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            id
        }
    }

    suspend fun bindSubject(
        context: Context,
        projectId: String,
        subjectType: String,
        subjectId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        requireSubject(subjectType, subjectId)
        val database = ProjectDatabase.get(context)
        database.withTransaction {
            val dao = database.projectDao()
            if (dao.project(projectId)?.archivedAt != null) throw ProjectException("PROJECT_NOT_ACTIVE")
            if (dao.project(projectId) == null) throw ProjectException("PROJECT_NOT_FOUND")
            dao.upsertBinding(
                ProjectSubjectBindingEntity(
                    projectId = projectId,
                    subjectType = subjectType,
                    subjectId = subjectId,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    suspend fun bindingForSubject(
        context: Context,
        subjectType: String,
        subjectId: String,
    ): ProjectSubjectBinding? {
        requireSubject(subjectType, subjectId)
        return ProjectDatabase.get(context).projectDao()
            .activeBinding(subjectType, subjectId)?.toModel()
    }

    suspend fun bindingsForProject(context: Context, projectId: String): List<ProjectSubjectBinding> =
        ProjectDatabase.get(context).projectDao().bindingsForProject(projectId).map { it.toModel() }

    suspend fun subjectOptions(context: Context): List<ProjectSubjectOption> = withContext(Dispatchers.IO) {
        val projects = projects(context).first().associateBy { it.id }
        val bindings = ProjectDatabase.get(context).projectDao().allBindings()
            .associateBy { it.subjectType to it.subjectId }
        fun option(type: String, id: String, title: String, updatedAt: Long): ProjectSubjectOption {
            val projectId = bindings[type to id]?.projectId
            return ProjectSubjectOption(type, id, title, updatedAt, projectId, projects[projectId]?.name)
        }
        val conversations = io.github.mangi.eta.data.db.EtaDatabase.get(context).conversationDao()
            .conversationIdentities().map { option(ProjectSubjectType.CONVERSATION, it.id, it.title, it.updatedAt) }
        val rooms = io.github.mangi.eta.fork.meeting.MeetingRepository.rooms(context).first()
            .map { option(ProjectSubjectType.MEETING, it.id, it.title, it.updatedAt) }
        (conversations + rooms).sortedByDescending { it.updatedAt }
    }

    suspend fun bindSubjectIfUnchanged(context: Context, projectId: String, option: ProjectSubjectOption) {
        requireSubject(option.type, option.id)
        val exists = when (option.type) {
            ProjectSubjectType.CONVERSATION -> io.github.mangi.eta.data.db.EtaDatabase.get(context).conversationDao().conversationExists(option.id)
            ProjectSubjectType.MEETING -> io.github.mangi.eta.fork.meeting.MeetingRepository.room(context, option.id).first() != null
            else -> false
        }
        if (!exists) throw ProjectException("SUBJECT_NOT_FOUND")
        val database = ProjectDatabase.get(context)
        database.withTransaction {
            val dao = database.projectDao()
            val project = dao.project(projectId) ?: throw ProjectException("PROJECT_NOT_FOUND")
            if (project.archivedAt != null) throw ProjectException("PROJECT_NOT_ACTIVE")
            if (dao.binding(option.type, option.id)?.projectId != option.projectId) throw ProjectException("BINDING_CHANGED")
            val now = System.currentTimeMillis()
            dao.upsertBinding(ProjectSubjectBindingEntity(projectId, option.type, option.id, now, now))
        }
    }

    suspend fun unbindSubject(context: Context, subjectType: String, subjectId: String) {
        requireSubject(subjectType, subjectId)
        ProjectDatabase.get(context).projectDao().deleteBinding(subjectType, subjectId)
    }

    suspend fun rename(context: Context, projectId: String, name: String, now: Long = System.currentTimeMillis()) {
        val normalized = normalizeName(name)
        if (ProjectDatabase.get(context).projectDao().rename(projectId, normalized, now) == 0) {
            throw ProjectException("PROJECT_NOT_FOUND")
        }
    }

    suspend fun setArchived(context: Context, projectId: String, archived: Boolean, now: Long = System.currentTimeMillis()) {
        if (ProjectDatabase.get(context).projectDao().setArchived(projectId, now.takeIf { archived }, now) == 0) {
            throw ProjectException("PROJECT_NOT_FOUND")
        }
    }

    suspend fun delete(context: Context, projectId: String) {
        if (ProjectDatabase.get(context).projectDao().deleteProject(projectId) == 0) {
            throw ProjectException("PROJECT_NOT_FOUND")
        }
    }

    private fun normalizeDraft(draft: ProjectDraft): ProjectDraft = draft.copy(
        name = normalizeName(draft.name),
        providerId = normalizeRequired(draft.providerId, "PROVIDER_INVALID", 256),
        rootIdentityKey = normalizeRequired(draft.rootIdentityKey, "ROOT_INVALID", 1_024),
        rootDisplayName = normalizeRequired(draft.rootDisplayName, "ROOT_NAME_INVALID", 255),
        source = normalizeRequired(draft.source, "SOURCE_INVALID", 64),
    )

    internal fun validateSafRoot(uri: android.net.Uri, hasReadWriteGrant: Boolean) {
        require(uri.scheme == "content" && android.provider.DocumentsContract.isTreeUri(uri)) { "ROOT_INVALID" }
        require(hasReadWriteGrant) { "ROOT_PERMISSION_REQUIRED" }
    }

    private fun normalizeName(name: String): String = name.trim().also {
        if (it.isEmpty() || it.length > 120 || it.any(Char::isISOControl)) {
            throw ProjectException("NAME_INVALID")
        }
    }

    private fun normalizeRequired(value: String, code: String, maxLength: Int): String = value.trim().also {
        if (it.isEmpty() || it.length > maxLength || it.any(Char::isISOControl)) throw ProjectException(code)
    }

    private fun requireSubject(subjectType: String, subjectId: String) {
        if (subjectType !in setOf(ProjectSubjectType.CONVERSATION, ProjectSubjectType.MEETING)) {
            throw ProjectException("SUBJECT_TYPE_INVALID")
        }
        if (subjectId.isBlank() || subjectId.length > 256 || subjectId.any(Char::isISOControl)) {
            throw ProjectException("SUBJECT_ID_INVALID")
        }
    }
}
