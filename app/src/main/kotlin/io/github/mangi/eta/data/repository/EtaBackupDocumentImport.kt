package io.github.mangi.eta.data.repository

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/** SAF access is interruptible; validation and restore always read a stable local snapshot. */
internal object EtaBackupDocumentImport {
    suspend fun <T> read(context: Context, uri: Uri, progress: (EtaBackupProgress) -> Unit,
        consume: suspend (InputStream) -> T): T = withContext(Dispatchers.IO) {
        val file = File.createTempFile("eta-document-import-", ".tmp", context.cacheDir)
        try {
            val caller = currentCoroutineContext()
            runInterruptible {
                progress(EtaBackupProgress("read_document"))
                val input = context.contentResolver.openInputStream(uri) ?: error("无法打开备份文件")
                input.use { source -> file.outputStream().buffered(256 * 1024).use { target ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    var lastUpdate = 0L
                    while (true) {
                        caller.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        copied += count
                        check(copied <= 9L * 1024 * 1024 * 1024) { "备份文件超过大小限制" }
                        target.write(buffer, 0, count)
                        val now = System.nanoTime()
                        if (now - lastUpdate >= 200_000_000L) { progress(EtaBackupProgress("read_document", bytes = copied)); lastUpdate = now }
                    }
                } }
            }
            currentCoroutineContext().ensureActive()
            file.inputStream().buffered().use { consume(it) }
        } finally { file.delete() }
    }
}
