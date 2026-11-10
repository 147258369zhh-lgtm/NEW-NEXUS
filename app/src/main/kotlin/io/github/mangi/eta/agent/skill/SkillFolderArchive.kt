package io.github.mangi.eta.agent.skill

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Converts a user-selected document tree into the same bounded ZIP accepted by SkillPackageInstaller. */
internal object SkillFolderArchive {
    fun create(context: Context, treeUri: Uri, destination: File): Int {
        require(treeUri.scheme == "content") { "请选择一个可读取的本地文件夹" }
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        destination.parentFile?.let { parent ->
            check(parent.mkdirs() || parent.isDirectory) { "无法创建 Skill 导入缓存" }
        }
        var files = 0
        var totalBytes = 0L
        val paths = hashSetOf<String>()
        try {
            ZipOutputStream(FileOutputStream(destination).buffered()).use { zip ->
                fun visit(directoryUri: Uri, relativeDirectory: String, depth: Int) {
                    require(depth <= MAX_DEPTH) { "文件夹层级超过 $MAX_DEPTH 层" }
                    val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(directoryUri))
                    context.contentResolver.query(
                        children,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                        ),
                        null,
                        null,
                        null,
                    )?.use { cursor ->
                        val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        while (cursor.moveToNext()) {
                            val name = safeSegment(cursor.getString(nameIndex))
                            val relative = if (relativeDirectory.isBlank()) name else "$relativeDirectory/$name"
                            val collision = relative.lowercase(Locale.ROOT)
                            require(paths.add(collision)) { "文件夹包含重名路径：$relative" }
                            val childUri = DocumentsContract.buildDocumentUriUsingTree(
                                treeUri,
                                cursor.getString(idIndex),
                            )
                            if (cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR) {
                                visit(childUri, relative, depth + 1)
                            } else {
                                files += 1
                                require(files <= MAX_FILES) { "文件夹文件数量超过 $MAX_FILES 个" }
                                zip.putNextEntry(ZipEntry(relative))
                                var fileBytes = 0L
                                val input = context.contentResolver.openInputStream(childUri)
                                    ?: throw IllegalArgumentException("无法读取文件：$relative")
                                input.use { stream ->
                                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                    while (true) {
                                        val count = stream.read(buffer)
                                        if (count < 0) break
                                        fileBytes += count
                                        totalBytes += count
                                        require(fileBytes <= MAX_FILE_BYTES) { "文件过大：$relative" }
                                        require(totalBytes <= MAX_TOTAL_BYTES) { "文件夹内容超过 128 MiB" }
                                        zip.write(buffer, 0, count)
                                    }
                                }
                                zip.closeEntry()
                            }
                        }
                    } ?: throw IllegalArgumentException("无法读取所选文件夹")
                }
                visit(rootUri, "", 0)
            }
            require(files > 0) { "所选文件夹为空" }
            return files
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }

    private fun safeSegment(raw: String): String {
        val value = raw.trim()
        require(value.isNotBlank() && value.length <= 255 && value !in setOf(".", "..")) {
            "文件夹包含无效名称"
        }
        require('/' !in value && '\\' !in value && value.none(Char::isISOControl)) {
            "文件夹包含不安全名称"
        }
        return value
    }

    private const val MAX_FILES = 2_048
    private const val MAX_DEPTH = 16
    private const val MAX_FILE_BYTES = 32L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 128L * 1024L * 1024L
}
