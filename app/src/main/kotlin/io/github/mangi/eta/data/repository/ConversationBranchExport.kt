package io.github.mangi.eta.data.repository

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import io.github.mangi.eta.data.db.ConversationBranchEntity
import io.github.mangi.eta.data.db.EtaDatabase
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 图结构诊断导出不包含会话标题、消息正文、工具参数、凭据或附件。 */
internal object ConversationBranchExport {
    const val MAX_NODES = 4096
    const val MAX_BYTES = 2L * 1024 * 1024
    private val json = Json { encodeDefaults = true }

    suspend fun export(context: Context, destination: Uri, conversationId: String?, allFamilies: Boolean): Int =
        withContext(Dispatchers.IO) {
            withTimeout(60_000L) {
                val db = EtaDatabase.get(context)
                val nodes = db.withTransaction {
                    val dao = db.conversationDao()
                    val family = conversationId?.let { dao.branch(it)?.familyId ?: it }
                    val result = mutableListOf<ConversationBranchEntity>()
                    var offset = 0
                    while (true) {
                        ensureActive()
                        val rows = dao.branchesPage(128, offset)
                        rows.filterTo(result) { allFamilies || it.familyId == family }
                        if (result.size > MAX_NODES) throw IOException("BRANCH_GRAPH_TOO_LARGE")
                        if (rows.size < 128) break
                        offset += rows.size
                    }
                    if (!allFamilies && result.isEmpty()) conversationId?.let { id ->
                        dao.conversation(id)?.let { result += ConversationBranchEntity(id, id, createdAt = it.createdAt) }
                    }
                    result
                }
                val temporary = File.createTempFile("eta-branches-", ".json", context.cacheDir)
                try {
                    temporary.outputStream().buffered().use { writeDocument(it, nodes) }
                    ensureActive()
                    val caller = currentCoroutineContext()
                    runInterruptible {
                        val stream = context.contentResolver.openOutputStream(destination, "wt")
                            ?: throw IOException("BRANCH_DOCUMENT_UNAVAILABLE")
                        stream.buffered().use { target -> temporary.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                caller.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                target.write(buffer, 0, count)
                            }
                        } }
                    }
                    ensureActive()
                    nodes.size
                } finally { temporary.delete() }
            }
        }

    internal suspend fun writeDocument(output: OutputStream, nodes: List<ConversationBranchEntity>) {
        require(nodes.size <= MAX_NODES)
        var bytes = 0L
        fun write(text: String) {
            val data = text.toByteArray(Charsets.UTF_8)
            bytes += data.size
            if (bytes > MAX_BYTES) throw IOException("BRANCH_GRAPH_TOO_LARGE")
            output.write(data)
        }
        write("{\"type\":\"eta_conversation_branch_graph\",\"version\":1,\"privacy\":\"structure_only_no_titles_messages_credentials_or_attachments\",\"nodes\":[")
        nodes.forEachIndexed { i, node ->
            currentCoroutineContext().ensureActive()
            if (i > 0) write(",")
            write(json.encodeToString(node))
        }
        write("]}")
        output.flush()
    }
}
