package io.github.mangi.eta.data.repository

import android.Manifest
import android.app.Application
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AutoBackupDocumentsTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var provider: BackupTestDocumentsProvider
    private val settings = AutoBackupSettings(tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root").toString(), keep = 1)
    private val summary = EtaBackupSummary(0, 0, 0, 0, 0)

    @Before fun registerProvider() {
        provider = BackupTestDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply {
            authority = AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    @Test fun publishesCompleteBytesBeforePruningOnlyOwnedNames() = runBlocking {
        provider.seed("Eta-auto-20250101-030000-1234abcd.eta-backup")
        val unrelated = listOf("notes.json", "Eta-auto-manual.json", "Eta-auto-20250101-030000-1234abcd.eta-backup.part")
        unrelated.forEach(provider::seed)
        val bytes = ByteArray(600_000) { (it % 251).toByte() }
        AutoBackupDocuments.save(context, settings, null) { output, _, _ -> output.write(bytes); summary }
        assertTrue(AutoBackupStore.status(context), AutoBackupStore.status(context).startsWith("备份成功"))
        assertFalse("Names: ${provider.names()}", provider.names().contains("Eta-auto-20250101-030000-1234abcd.eta-backup"))
        val published = provider.names().single { it.startsWith("Eta-auto-") && it.endsWith(".eta-backup") }
        assertArrayEquals(bytes, provider.read(published))
        assertTrue(provider.names().containsAll(unrelated))
        assertFalse(provider.names().contains("Eta-auto-20250101-030000-1234abcd.eta-backup"))
        assertTrue(AutoBackupStore.lastSuccess(context) > 0)
    }

    @Test fun selectedFormatUsesJsonUnlessPasswordMakesItAPortableEncryptedContainer() = runBlocking {
        val selected = settings.copy(options = EtaBackupOptions(format = EtaBackupFormat.JSON, contents = setOf(EtaBackupContent.MEMORY)))
        AutoBackupDocuments.save(context, selected, password = null) { output, secret, _ ->
            org.junit.Assert.assertNull(secret)
            output.write(byteArrayOf(1))
            summary
        }
        assertTrue(provider.names().any { it.endsWith(".json") })
        AutoBackupDocuments.save(context, selected, password = "secret") { output, secret, _ ->
            assertEquals("secret", secret)
            output.write(byteArrayOf(2))
            summary
        }
        assertEquals(1, provider.names().count { it.endsWith(".eta-backup") })
    }

    @Test fun renameFailureRemovesPartialAndDoesNotDeletePreviousBackup() = runBlocking {
        provider.seed("Eta-auto-20250101-030000-1234abcd.eta-backup")
        provider.failRename = true
        val result = runCatching { AutoBackupDocuments.save(context, settings, null) { output, _, _ -> output.write(byteArrayOf(7)); summary } }
        assertTrue(result.isFailure)
        assertEquals(listOf("Eta-auto-20250101-030000-1234abcd.eta-backup"), provider.names())
        assertEquals(0L, AutoBackupStore.lastSuccess(context))
    }

    @Test fun cancellationAndPreparationErrorsNeverPublishPartialDocuments() = runBlocking {
        listOf(IOException("fixture"), CancellationException("fixture")).forEach { failure ->
            val result = runCatching { AutoBackupDocuments.save(context, settings, null) { output, _, _ -> output.write(byteArrayOf(9)); throw failure } }
            assertEquals(failure.javaClass, result.exceptionOrNull()?.javaClass)
            assertEquals(failure.message, result.exceptionOrNull()?.message)
            assertTrue(provider.names().isEmpty())
            assertEquals(0L, AutoBackupStore.lastSuccess(context))
        }
    }

    @Test fun cleanupFailurePreservesNewBackupAndSuccessfulTimestamp() = runBlocking {
        val old = "Eta-auto-20250101-030000-1234abcd.eta-backup"
        provider.seed(old)
        provider.failDeleteName = old
        AutoBackupDocuments.save(context, settings, "secret") { output, password, _ -> assertEquals("secret", password); output.write(byteArrayOf(8)); summary }
        assertEquals(2, provider.names().size)
        assertTrue(AutoBackupStore.lastSuccess(context) > 0)
        assertTrue(AutoBackupStore.status(context).startsWith("备份已保存，旧备份清理失败"))
    }

    companion object { const val AUTHORITY = "eta.backup.test.documents" }
}

class BackupTestDocumentsProvider : DocumentsProvider() {
    private data class Document(var name: String, val file: File)
    private val documents = linkedMapOf<String, Document>()
    private var sequence = 0
    var failRename = false
    var failDeleteName: String? = null
    override fun onCreate() = true
    fun names() = documents.values.map { it.name }
    fun seed(name: String) { createDocument("root", "application/octet-stream", name) }
    fun read(name: String) = documents.values.single { it.name == name }.file.readBytes()
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val id = "file-${++sequence}"
        documents[id] = Document(displayName, File.createTempFile("backup-provider-", ".bin", requireNotNull(context).cacheDir))
        return id
    }
    override fun renameDocument(documentId: String, displayName: String): String {
        if (failRename) throw IOException("rename failed")
        requireNotNull(documents[documentId]).name = displayName
        return documentId
    }
    override fun deleteDocument(documentId: String) {
        val document = documents[documentId] ?: return
        if (document.name == failDeleteName) throw IOException("delete failed")
        document.file.delete()
        documents.remove(documentId)
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == "root" && documentId in documents
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = rows(listOf(documentId), projection)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = rows(documents.keys.toList(), projection)
    private fun rows(ids: List<String>, projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return MatrixCursor(columns).apply { ids.forEach { id -> addRow(columns.map { column -> when (column) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> documents[id]?.name ?: "Backups"
            DocumentsContract.Document.COLUMN_MIME_TYPE -> if (id == "root") DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
            DocumentsContract.Document.COLUMN_FLAGS -> 0
            else -> null
        } }) } }
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(requireNotNull(documents[documentId]).file, ParcelFileDescriptor.parseMode(mode))
}
