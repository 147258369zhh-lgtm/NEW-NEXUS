package io.github.mangi.eta.fork.projects

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import java.io.InputStream
import java.io.OutputStream

internal data class ProjectDocument(val id: String, val name: String, val directory: Boolean, val size: Long?, val modified: Long?, val flags: Int)

/** Uses only the explicitly selected document tree; document IDs remain opaque provider IDs. */
internal class SafProjectTree(
    private val context: Context,
    val uri: Uri,
    private val hasGrant: (Uri, Boolean) -> Boolean = { value, write ->
        context.contentResolver.persistedUriPermissions.any { it.uri == value && it.isReadPermission && (!write || it.isWritePermission) }
    },
) {
    val rootId = DocumentsContract.getTreeDocumentId(uri)
    init { require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "项目目录不是有效的授权文档目录" } }

    fun ensureAccess(write: Boolean = false) {
        RuntimeInvocationScope.controller?.throwIfCancelled()
        check(hasGrant(uri, write)) { "项目目录授权已失效，请重新选择目录" }
    }
    fun children(id: String): List<ProjectDocument> = query(DocumentsContract.buildChildDocumentsUriUsingTree(uri, id))
    fun document(id: String): ProjectDocument = query(documentUri(id)).singleOrNull() ?: error("项目文件不存在或不可读取")
    fun read(id: String): InputStream {
        ensureAccess()
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(open(id, "r"))
    }
    fun write(id: String): OutputStream {
        ensureAccess(true)
        return android.os.ParcelFileDescriptor.AutoCloseOutputStream(open(id, "wt"))
    }
    fun create(parentId: String, name: String, directory: Boolean): ProjectDocument {
        ensureAccess(true); validateName(name)
        val result = requireNotNull(DocumentsContract.createDocument(context.contentResolver, documentUri(parentId),
            if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream", name)) { "项目目录不能创建文件" }
        val node = document(DocumentsContract.getDocumentId(result))
        require(node.name == name && node.directory == directory) { "文档服务改变了创建名称" }
        return node
    }
    fun rename(id: String, name: String): ProjectDocument {
        ensureAccess(true); validateName(name)
        val result = requireNotNull(DocumentsContract.renameDocument(context.contentResolver, documentUri(id), name)) { "文档服务不能重命名文件" }
        return document(DocumentsContract.getDocumentId(result)).also { require(it.name == name) { "文档服务改变了发布名称" } }
    }
    fun delete(id: String) {
        ensureAccess(true)
        check(id != rootId && DocumentsContract.deleteDocument(context.contentResolver, documentUri(id))) { "临时文件清理失败" }
    }
    private fun documentUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(uri, id)
    private fun open(id: String, mode: String): android.os.ParcelFileDescriptor {
        val signal = CancellationSignal()
        val binding = RuntimeInvocationScope.controller?.register(signal::cancel)
        return try { requireNotNull(context.contentResolver.openFileDescriptor(documentUri(id), mode, signal)) { "项目文件无法打开" } }
        finally { binding?.close() }
    }
    private fun query(target: Uri): List<ProjectDocument> {
        ensureAccess()
        val signal = CancellationSignal()
        val binding = RuntimeInvocationScope.controller?.register(signal::cancel)
        try {
            return requireNotNull(context.contentResolver.query(target, COLUMNS, android.os.Bundle.EMPTY, signal)) { "无法读取项目目录" }.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        RuntimeInvocationScope.controller?.throwIfCancelled()
                        require(size < 2048) { "单个项目目录超过 2048 个条目" }
                        val name = cursor.getString(1)
                        validateName(name)
                        add(ProjectDocument(cursor.getString(0), name, cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                            if (cursor.isNull(3)) null else cursor.getLong(3), if (cursor.isNull(4)) null else cursor.getLong(4), cursor.getInt(5)))
                    }
                }
            }
        } finally { binding?.close() }
    }
    companion object {
        private val COLUMNS = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS)
        fun validateName(value: String) {
            require(value.isNotBlank() && value.length <= 255 && value !in setOf(".", "..") &&
                value.none { it == '/' || it == '\\' || it.isISOControl() }) { "项目包含不能安全使用的文件名" }
        }
    }
}
