package io.github.mangi.eta.fork.projects

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal object ProjectWorkspaceRepository {
    data class Copy(val projectId: String, val runId: String, val relativePath: String, val createdAt: Long)
    fun copies(context: Context, projectId: String): List<Copy> {
        val workspace = io.github.mangi.eta.agent.terminal.TerminalPrivateStorage.workspace(context.filesDir).canonicalFile
        val key = java.security.MessageDigest.getInstance("SHA-256").digest(projectId.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val directory = java.io.File(workspace, "projects/$key")
        if (!directory.exists()) return emptyList()
        val roots = checkNotNull(directory.listFiles()) { "项目工作副本目录无法读取" }
        return roots.mapNotNull { root -> runCatching {
            if (!root.isDirectory || java.nio.file.Files.isSymbolicLink(root.toPath()) || root.name.startsWith('.')) return@runCatching null
            val manifest = java.io.File(root, "binding.json")
            val files = java.io.File(root, "files")
            if (!manifest.isFile || !files.isDirectory || manifest.length() > 2 * 1024 * 1024 ||
                java.nio.file.Files.isSymbolicLink(manifest.toPath()) || java.nio.file.Files.isSymbolicLink(files.toPath()) ||
                root.canonicalFile.parentFile != directory.canonicalFile || files.canonicalFile.parentFile != root.canonicalFile) return@runCatching null
            val record = org.json.JSONObject(manifest.readText())
            val runId = record.opt("run_id") as? String ?: return@runCatching null
            if (record.optString("project_id") != projectId || runId.isBlank()) return@runCatching null
            Copy(projectId, runId, files.relativeTo(workspace).invariantSeparatorsPath, manifest.lastModified())
        }.getOrNull()
        }.sortedByDescending { it.createdAt }.take(64)
    }
    fun forRun(context: Context, projectId: String?, runId: String): ProjectWorkspace? {
        if (projectId == null) return null
        val dao = ProjectDatabase.get(context).projectDao()
        val project = runBlocking(Dispatchers.IO) { dao.project(projectId) } ?: error("项目已不存在")
        check(project.archivedAt == null) { "项目已归档" }
        return ProjectWorkspace.open(context, project, runId, projectAvailable = {
            runBlocking(Dispatchers.IO) { dao.project(projectId) }?.let { current ->
                current.archivedAt == null && current.rootIdentityKey == project.rootIdentityKey && current.source == project.source
            } == true
        })
    }
}
