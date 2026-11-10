package io.github.mangi.eta.fork.tasks

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import java.io.File
import org.json.JSONObject

internal object AutomationInputFiles {
    private const val LIMIT = 32L * 1024 * 1024
    fun import(context: Context, runId: String, payload: JSONObject): JSONObject {
        val raw = payload.optString("fileUri")
        if (raw.isBlank()) return payload
        val uri = Uri.parse(raw); require(uri.scheme == "content")
        var name = "input.bin"
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            Bundle.EMPTY,
            null,
        ).use {
            require(it != null && it.moveToFirst()) { "触发文件不可读" }
            name = it.getString(0).orEmpty().replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(120).ifBlank { "input.bin" }
            require(it.isNull(1) || it.getLong(1) <= LIMIT) { "触发文件超过 32 MiB" }
        }
        val root = File(context.filesDir, "automation-inputs")
        root.listFiles().orEmpty().filter { it.isDirectory && it.canonicalFile.parentFile == root.canonicalFile &&
            !java.nio.file.Files.isSymbolicLink(it.toPath()) && System.currentTimeMillis() - it.lastModified() > 7L * 86400000 }
            .forEach { directory -> directory.listFiles().orEmpty().filter { it.isFile && it.canonicalFile.parentFile == directory.canonicalFile }.forEach { it.delete() }; directory.delete() }
        require(root.walkTopDown().filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.sumOf { it.length() } + LIMIT <= 256L * 1024 * 1024) { "自动任务输入副本已达到 256 MiB 上限，请清理旧副本" }
        val directory = File(root, runId.replace(Regex("[^a-zA-Z0-9_-]"), "_")).apply { mkdirs() }
        require(directory.canonicalFile.parentFile == root.canonicalFile)
        val target = File(directory, name)
        require(target.canonicalFile.parentFile == directory.canonicalFile)
        val partial = File(directory, ".input.part")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input)
                partial.outputStream().use { output ->
                    val buffer = ByteArray(32768); var total = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; require(total <= LIMIT) { "触发文件超过 32 MiB" }; output.write(buffer, 0, n)
                    }
                }
            }
            check(partial.renameTo(target)) { "导入副本失败" }
            return JSONObject(payload.toString()).put("importedPath", target.absolutePath).put("name", name).put("isCopy", true)
        } finally { partial.delete() }
    }
}
