package io.github.mangi.eta.fork.tasks

import android.Manifest
import android.app.Application
import android.content.pm.ProviderInfo
import android.os.Bundle
import android.provider.DocumentsContract
import io.github.mangi.eta.data.repository.BackupTestDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37], application = Application::class)
class AutomationFilePipelineTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val authority = "eta.automation.test.documents"
    private lateinit var provider: BackupTestDocumentsProvider
    @Before fun setup() {
        AgentTaskDatabase.closeForTests(); context.deleteDatabase("eta_task.db")
        provider = BackupTestDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = this@AutomationFilePipelineTest.authority; exported = true; grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS; writePermission = Manifest.permission.MANAGE_DOCUMENTS })
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }
    @After fun close() { AgentTaskDatabase.closeForTests() }

    @Test fun baselineThenNewExcelQueuesOnceAndImportsToolReadableBytes() = runBlocking {
        val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        ), Bundle.EMPTY, null)?.close()
        val db = AgentTaskDatabase.get(context)
        val id = AutomationRepository(db).save(null, "表格处理", "读取触发文件并核对表格", AutomationSpec(),
            AutomationTriggerEntity("", "", AutomationTrigger.FILE_CREATED.name, tree.toString(), "xlsx"), null)
        provider.seed("existing.xlsx")
        AutomationTriggers.poll(context)
        assertTrue(db.agentTaskDao().runsForDefinition(id).isEmpty())
        provider.seed("new-report.xlsx"); provider.seed("notes.txt")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "file-2")
        val bytes = byteArrayOf(80, 75, 3, 4, 42)
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        AutomationTriggers.poll(context); AutomationTriggers.poll(context)
        val runs = db.agentTaskDao().runsForDefinition(id)
        val trigger = db.triggerDao().get("trigger:$id")
        assertEquals("expected one run; trigger=${trigger?.detail.orEmpty()}", 1, runs.size)
        val run = runs.single()
        val payload = JSONObject(db.agentTaskDao().events(run.id).single().payloadJson)
        assertEquals(uri.toString(), payload.getString("fileUri"))
        val imported = AutomationInputFiles.import(context, run.id, payload)
        assertEquals("new-report.xlsx", imported.getString("name")); assertTrue(imported.getBoolean("isCopy"))
        assertArrayEquals(bytes, File(imported.getString("importedPath")).readBytes())
        assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }
}
