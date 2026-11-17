package io.github.mangi.eta.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.fork.meeting.MeetingDatabase
import io.github.mangi.eta.fork.projects.ProjectDatabase
import io.github.mangi.eta.fork.projects.ProjectSubjectType
import io.github.mangi.eta.fork.projects.SafProjectTree
import io.github.mangi.eta.fork.projects.SafProjectTree.Companion.validateName
import io.github.zhh_lgtm.nexus.application.api.ArtifactRecord
import io.github.zhh_lgtm.nexus.application.api.ArtifactStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal data class GeneratedFilesArchiveSettings(
    val tree: String = "",
    val displayName: String = "",
    val enabled: Boolean = false,
) {
    val configured: Boolean get() = tree.isNotBlank()
}

internal object GeneratedFilesArchiveSettingsStore {
    private const val PREFERENCES = "generated_files_archive"
    private const val KEY_TREE = "tree"
    private const val KEY_NAME = "display_name"
    private const val KEY_ENABLED = "enabled"

    fun read(context: Context): GeneratedFilesArchiveSettings {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return GeneratedFilesArchiveSettings(
            tree = preferences.getString(KEY_TREE, "").orEmpty(),
            displayName = preferences.getString(KEY_NAME, "").orEmpty(),
            enabled = preferences.getBoolean(KEY_ENABLED, false),
        )
    }

    fun selectTree(context: Context, uri: Uri, displayName: String): GeneratedFilesArchiveSettings {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "GENERATED_FILES_ROOT_INVALID" }
        val resolver = context.applicationContext.contentResolver
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        check(resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }) { "GENERATED_FILES_ROOT_WRITE_PERMISSION_REQUIRED" }
        val value = GeneratedFilesArchiveSettings(
            tree = uri.toString(),
            displayName = displayName.trim().take(160),
            enabled = true,
        )
        write(context, value)
        return value
    }

    fun setEnabled(context: Context, enabled: Boolean): GeneratedFilesArchiveSettings {
        val current = read(context)
        if (enabled) {
            check(current.configured) { "GENERATED_FILES_ROOT_REQUIRED" }
            val uri = current.tree.toUri()
            check(context.applicationContext.contentResolver.persistedUriPermissions.any {
                it.uri == uri && it.isReadPermission && it.isWritePermission
            }) { "GENERATED_FILES_ROOT_WRITE_PERMISSION_REQUIRED" }
        }
        return current.copy(enabled = enabled).also { write(context, it) }
    }

    private fun write(context: Context, value: GeneratedFilesArchiveSettings) {
        check(context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY_TREE, value.tree)
            .putString(KEY_NAME, value.displayName)
            .putBoolean(KEY_ENABLED, value.enabled)
            .commit()) { "GENERATED_FILES_SETTINGS_UNAVAILABLE" }
    }
}

internal object GeneratedFilesArchive {
    data class Published(val uri: String, val relativePath: String)

    fun publish(
        context: Context,
        source: File,
        runId: String,
        projectId: String?,
        subjectType: String?,
        subjectId: String?,
        label: String,
    ): Published? {
        val settings = GeneratedFilesArchiveSettingsStore.read(context)
        if (!settings.enabled || !settings.configured || !source.isFile) return null
        require(source.length() in 1..MAX_FILE_BYTES) { "GENERATED_FILE_SIZE_INVALID" }
        RuntimeInvocationScope.controller?.throwIfCancelled()
        val tree = SafProjectTree(context.applicationContext, settings.tree.toUri())
        tree.ensureAccess(write = true)
        val directories = archiveDirectories(context, projectId, subjectType, subjectId)
        var parent = tree.rootId
        directories.forEach { requested ->
            RuntimeInvocationScope.controller?.throwIfCancelled()
            parent = ensureDirectory(tree, parent, requested)
        }
        val desiredName = GeneratedFilesArchivePlan.safeName(label, "nexus-${runId.takeLast(8)}.bin")
        val finalName = GeneratedFilesArchivePlan.uniqueFileName(desiredName, tree.children(parent))
        val temporaryName = ".nexus-${UUID.randomUUID()}.part"
        val temporary = tree.create(parent, temporaryName, directory = false)
        var published = false
        try {
            tree.write(temporary.id).use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        RuntimeInvocationScope.controller?.throwIfCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_FILE_BYTES) { "GENERATED_FILE_SIZE_INVALID" }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            val document = tree.rename(temporary.id, finalName)
            published = true
            return Published(
                uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree.uri, document.id).toString(),
                relativePath = (directories + finalName).joinToString("/"),
            )
        } finally {
            if (!published) runCatching { tree.delete(temporary.id) }
        }
    }

    fun publishArtifact(context: Context, artifact: ArtifactRecord): Published? {
        if (artifact.status != ArtifactStatus.VERIFIED) return null
        val source = when (artifact.location) {
            "workspace" -> io.github.mangi.eta.agent.terminal.TerminalPrivateStorage.workspace(context.filesDir)
                .resolve(artifact.path)
            "device" -> File(artifact.path)
            else -> return null
        }
        if (!source.isFile) return null
        return publish(
            context = context,
            source = source,
            runId = artifact.runId,
            projectId = artifact.projectId,
            subjectType = artifact.subjectType,
            subjectId = artifact.subjectId,
            label = artifact.label.ifBlank { source.name },
        )
    }

    private fun archiveDirectories(
        context: Context,
        projectId: String?,
        subjectType: String?,
        subjectId: String?,
    ): List<String> = runBlocking(Dispatchers.IO) {
        val projectDao = ProjectDatabase.get(context.applicationContext).projectDao()
        val binding = if (projectId == null && subjectType != null && subjectId != null) {
            projectDao.activeBinding(subjectType, subjectId)
        } else null
        val project = (projectId ?: binding?.projectId)?.let { projectDao.project(it) }
        val projectName = GeneratedFilesArchivePlan.safeName(project?.name, "未归类会话")
        val subjectName = when (subjectType) {
            ProjectSubjectType.CONVERSATION -> subjectId?.let {
                EtaDatabaseAccess.conversationTitle(context, it)
            }
            ProjectSubjectType.MEETING -> subjectId?.let {
                MeetingDatabase.get(context.applicationContext).meetingDao().room(it)?.title
            }
            else -> null
        }
        listOf(projectName, GeneratedFilesArchivePlan.safeName(subjectName, "未命名会话"))
    }

    private fun ensureDirectory(tree: SafProjectTree, parentId: String, requestedName: String): String {
        val existing = tree.children(parentId).firstOrNull { it.directory && it.name == requestedName }
        if (existing != null) return existing.id
        var candidate = requestedName
        var sequence = 2
        while (tree.children(parentId).any { it.name == candidate }) {
            candidate = GeneratedFilesArchivePlan.withSequence(requestedName, sequence++)
        }
        return tree.create(parentId, candidate, directory = true).id
    }

    private object EtaDatabaseAccess {
        fun conversationTitle(context: Context, id: String): String? = runBlocking(Dispatchers.IO) {
            io.github.mangi.eta.data.db.EtaDatabase.get(context.applicationContext)
                .conversationDao().conversation(id)?.title
        }
    }

    private const val MAX_FILE_BYTES = 32L * 1024 * 1024
}

internal object GeneratedFilesPublicationStore {
    private const val PREFERENCES = "generated_files_publications"
    private const val MAX_ENTRIES = 2048

    fun read(context: Context, artifactId: String): GeneratedFilesArchive.Published? {
        val value = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(artifactId, null) ?: return null
        val parts = value.split('\n', limit = 3)
        if (parts.size != 3) return null
        return GeneratedFilesArchive.Published(uri = parts[1], relativePath = parts[2])
    }

    fun write(context: Context, artifactId: String, published: GeneratedFilesArchive.Published) {
        val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        synchronized(preferences) {
            val existing = preferences.all.entries
                .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
                .sortedBy { it.second.substringBefore('\n').toLongOrNull() ?: 0L }
            val editor = preferences.edit().putString(
                artifactId,
                "${System.currentTimeMillis()}\n${published.uri}\n${published.relativePath}",
            )
            existing.take((existing.size - MAX_ENTRIES + 1).coerceAtLeast(0)).forEach { (key, _) ->
                editor.remove(key)
            }
            check(editor.commit()) { "GENERATED_FILE_PUBLICATION_UNAVAILABLE" }
        }
    }
}

internal object GeneratedFilesArchivePlan {
    private val unsafe = Regex("[\\u0000-\\u001f\\u007f\\\\/:*?\"<>|]")

    fun safeName(value: String?, fallback: String): String {
        val normalized = value.orEmpty().trim()
            .replace(unsafe, "_")
            .replace(Regex("_+"), "_")
            .trim(' ', '.')
            .take(96)
            .ifBlank { fallback }
        validateName(normalized)
        return normalized
    }

    fun uniqueFileName(desired: String, existing: List<io.github.mangi.eta.fork.projects.ProjectDocument>): String {
        validateName(desired)
        val names = existing.mapTo(hashSetOf()) { it.name.lowercase() }
        if (desired.lowercase() !in names) return desired
        val dot = desired.lastIndexOf('.').takeIf { it > 0 }
        val base = if (dot == null) desired else desired.substring(0, dot)
        val extension = if (dot == null) "" else desired.substring(dot)
        var sequence = 2
        while (true) {
            val candidate = withSequence(base, sequence++) + extension
            if (candidate.lowercase() !in names) return candidate
        }
    }

    fun withSequence(base: String, sequence: Int): String = "$base-$sequence".take(96).trimEnd(' ', '.')
}
