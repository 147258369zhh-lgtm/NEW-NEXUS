package io.github.mangi.eta.data.repository

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal data class EtaBackupProgress(
    val phase: String,
    val files: Int = 0,
    val records: Long = 0,
    val bytes: Long = 0,
    val totalBytes: Long? = null,
)

/** Prepare locally before opening the document; SAF providers may be slow or remote. */
internal object EtaBackupDocumentExport {
    suspend fun export(
        context: Context,
        destination: Uri,
        password: String?,
        onProgress: (EtaBackupProgress) -> Unit = {},
        prepare: suspend (OutputStream, String?, (EtaBackupProgress) -> Unit) -> EtaBackupSummary,
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        val temporary = File.createTempFile("eta-document-export-", ".tmp", context.cacheDir)
        var destinationOpened = false
        try {
            val summary = temporary.outputStream().buffered(COPY_BUFFER_BYTES).use { prepare(it, password, onProgress) }
            ensureActive()
            val caller = currentCoroutineContext()
            runInterruptible {
                onProgress(EtaBackupProgress("copy_document", totalBytes = temporary.length()))
                val output = openDestination(context, destination)
                destinationOpened = true
                output.buffered(COPY_BUFFER_BYTES).use { target ->
                    temporary.inputStream().use { source ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var copied = 0L
                        var lastUpdate = 0L
                        while (true) {
                            caller.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count)
                            copied += count
                            val now = System.nanoTime()
                            if (now - lastUpdate >= 200_000_000L) {
                                onProgress(EtaBackupProgress("copy_document", bytes = copied, totalBytes = temporary.length()))
                                lastUpdate = now
                            }
                        }
                    }
                }
            }
            ensureActive()
            onProgress(
                EtaBackupProgress(
                    phase = "complete",
                    files = summary.privateFileCount,
                    bytes = temporary.length(),
                    totalBytes = temporary.length(),
                ),
            )
            summary
        } catch (failure: Throwable) {
            // A failed destination must not look like a usable backup in the document picker.
            runCatching {
                if (destinationOpened && DocumentsContract.isDocumentUri(context, destination)) DocumentsContract.deleteDocument(context.contentResolver, destination)
            }
            throw failure
        } finally {
            temporary.delete()
        }
    }

    private fun openDestination(context: Context, destination: Uri): OutputStream {
        // Some DocumentsProvider implementations only implement the legacy truncate mode.
        val truncate = runCatching {
            context.contentResolver.openOutputStream(destination, "wt")
        }.getOrNull()
        return truncate ?: context.contentResolver.openOutputStream(destination, "w")
            ?: throw IOException("BACKUP_DOCUMENT_UNAVAILABLE")
    }

    private const val COPY_BUFFER_BYTES = 256 * 1024
}
