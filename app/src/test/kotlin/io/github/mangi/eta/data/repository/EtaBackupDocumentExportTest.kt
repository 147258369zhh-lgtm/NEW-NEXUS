package io.github.mangi.eta.data.repository

import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaBackupDocumentExportTest {
    @Test fun inputDocumentIsCopiedOffMainAndAlwaysCleansTheLocalSnapshot() = runBlocking {
        val uri = Uri.parse("content://backup.test/input")
        val bytes = ByteArray(600_000) { (it % 97).toByte() }
        shadowOf(context.contentResolver).registerInputStream(uri, object : java.io.ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                return super.read(buffer, offset, length)
            }
        })
        val phases = mutableListOf<EtaBackupProgress>()
        val read = EtaBackupDocumentImport.read(context, uri, phases::add) { it.readBytes() }
        assertArrayEquals(bytes, read)
        assertTrue(phases.any { it.phase == "read_document" && it.bytes > 0 })
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("eta-document-import-") })
    }
    private val context = RuntimeEnvironment.getApplication()
    private val summary = EtaBackupSummary(1, 2, 3, 4, 5)
    private fun assertClean() = assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("eta-document-export-") })

    @Test fun preparationFailureDoesNotTruncateExistingDocument() = runBlocking {
        val destination = File(context.cacheDir, "existing.backup").apply { writeText("original") }
        val result = runCatching {
            EtaBackupDocumentExport.export(context, Uri.fromFile(destination), null) { output, _, _ ->
                output.write("partial".toByteArray())
                throw IOException("snapshot failed")
            }
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("original", destination.readText())
        assertClean()
    }

    @Test fun cancellingPreparationDoesNotPublishOrLeaveStagingFiles() = runBlocking {
        val destination = File(context.cacheDir, "cancelled.backup")
        val result = runCatching {
            EtaBackupDocumentExport.export(context, Uri.fromFile(destination), "password") { output, _, _ ->
                output.write("partial".toByteArray())
                throw CancellationException("cancelled")
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertFalse(destination.exists())
        assertClean()
    }

    @Test fun publishesBufferedBytesAndReportsCompletionOnlyAfterClose() = runBlocking {
        val uri = Uri.parse("content://backup.test/export")
        val bytes = ByteArray(600_000) { (it % 251).toByte() }
        val received = java.io.ByteArrayOutputStream()
        var closed = false
        var calls = 0
        shadowOf(context.contentResolver).registerOutputStream(uri, object : OutputStream() {
            override fun write(value: Int) = error("Document writes must be buffered")
            override fun write(buffer: ByteArray, offset: Int, length: Int) { calls++; received.write(buffer, offset, length) }
            override fun close() { closed = true }
        })
        val progress = mutableListOf<EtaBackupProgress>()
        val result = EtaBackupDocumentExport.export(context, uri, "password", onProgress = {
            if (it.phase == "complete") assertTrue(closed)
            progress += it
        }) { output, password, report ->
            assertEquals("password", password)
            report(EtaBackupProgress("private_data", files = 1, bytes = bytes.size.toLong()))
            output.write(bytes)
            summary
        }
        assertEquals(summary, result)
        assertArrayEquals(bytes, received.toByteArray())
        assertTrue(calls in 1..4)
        assertEquals("complete", progress.last().phase)
        assertClean()
    }

    @Test fun providerCloseFailureIsNotReportedAsSuccess() = runBlocking {
        val uri = Uri.parse("content://backup.test/failing-close")
        shadowOf(context.contentResolver).registerOutputStream(uri, object : java.io.ByteArrayOutputStream() {
            override fun close() { throw IOException("provider close failed") }
        })
        val phases = mutableListOf<String>()
        val result = runCatching {
            EtaBackupDocumentExport.export(context, uri, null, onProgress = { phases += it.phase }) { output, _, _ ->
                output.write(byteArrayOf(1, 2, 3))
                summary
            }
        }
        assertTrue(result.exceptionOrNull() is IOException)
        assertFalse("complete" in phases)
        assertClean()
    }

    @Test fun cancellationDuringPublicationStopsWithoutReportingCompletion() = runBlocking {
        val destination = File(context.cacheDir, "partial.backup")
        val phases = mutableListOf<String>()
        val result = runCatching {
            EtaBackupDocumentExport.export(context, Uri.fromFile(destination), null, onProgress = {
                phases += it.phase
                if (it.phase == "copy_document" && it.bytes > 0) throw CancellationException("cancel publication")
            }) { output, _, _ ->
                output.write(ByteArray(2 * 1024 * 1024) { 1 })
                summary
            }
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(destination.length() in 1 until (2 * 1024 * 1024).toLong())
        assertFalse("complete" in phases)
        assertClean()
    }

    @Test fun destinationWritingRunsOffTheMainThread() = runBlocking {
        val uri = Uri.parse("content://backup.test/io")
        shadowOf(context.contentResolver).registerOutputStream(uri, object : java.io.ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                assertNotEquals(android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                super.write(buffer, offset, length)
            }
        })
        EtaBackupDocumentExport.export(context, uri, null) { output, _, _ -> output.write(byteArrayOf(1)); summary }
        assertClean()
    }
}
