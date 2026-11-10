package io.github.mangi.eta.data.repository

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeStackTrace
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal object AutoBackupDocuments {
    suspend fun save(
        context: Context,
        settings: AutoBackupSettings,
        password: String?,
        prepare: suspend (OutputStream, String?, (EtaBackupProgress) -> Unit) -> EtaBackupSummary = { output, secret, report ->
            EtaBackupOperations.export(context, output, secret, settings.options, report)
        },
    ) = withContext(Dispatchers.IO) {
        check(settings.tree.isNotBlank()) { "请先选择自动备份目录" }
        val tree = Uri.parse(settings.tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        // Nanosecond ordering keeps same-second saves deterministic for retention pruning.
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSSSSSSSS"))
        val suffix = if (password != null) EtaBackupFormat.FULL.suffix else settings.options.format.suffix
        val name = "Nexus-auto-$stamp-${UUID.randomUUID().toString().take(8)}$suffix"
        val document = DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", "$name.part")
            ?: error("无法在所选目录创建备份，请重新授权目录")
        var published = false
        try {
            AutoBackupStore.status(context, "正在自动备份")
            EtaBackupDocumentExport.export(context, document, password, prepare = prepare)
            currentCoroutineContext().ensureActive()
            check(DocumentsContract.renameDocument(context.contentResolver, document, name) != null) { "备份写入完成，但文件发布失败" }
            published = true
            AutoBackupStore.status(context, "备份成功 · $name", success = true)
            try { prune(context, tree, settings.keep) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AndroidAgentLogger.warn("Automatic backup retention failed: ${failure.safeStackTrace()}")
                AutoBackupStore.status(context, "备份已保存，旧备份清理失败 · $name")
            }
        } finally {
            if (!published) runCatching { DocumentsContract.deleteDocument(context.contentResolver, document) }
        }
    }

    private fun prune(context: Context, tree: Uri, keep: Int) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val owned = mutableListOf<Pair<String, String>>()
        val cursor = context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            Bundle.EMPTY,
            null,
        )
            ?: error("无法读取目录")
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(1).orEmpty()
                if (OWNED_NAME.matches(name)) owned += it.getString(0) to name
            }
        }
        owned.sortedByDescending { it.second }.drop(keep.coerceAtLeast(1)).forEach { (id, _) ->
            check(DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))) { "旧备份清理失败" }
        }
    }

    private val OWNED_NAME = Regex(
        "Nexus-auto-[0-9]{8}-[0-9]{6}(?:-[0-9]{9})?-[a-f0-9]{8}\\.(eta-backup|json)",
    )
}
