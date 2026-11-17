package io.github.mangi.eta.data.repository

import android.Manifest
import android.app.Application
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.NexusExecutionRecorder
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.zhh_lgtm.nexus.kernel.api.HostId
import io.github.zhh_lgtm.nexus.kernel.api.RunBudget
import io.github.zhh_lgtm.nexus.kernel.api.RunId
import io.github.zhh_lgtm.nexus.kernel.api.RunMode
import io.github.zhh_lgtm.nexus.kernel.api.RunRequest
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import io.github.zhh_lgtm.nexus.application.api.ArtifactRecord
import io.github.zhh_lgtm.nexus.application.api.ArtifactStatus
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class GeneratedFilesArchiveTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: GeneratedFilesTestDocumentsProvider

    @Before fun setup() {
        provider = GeneratedFilesTestDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply {
            authority = AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    @Test fun archiveDirectorySelectionRejectsAFileDocumentUri() {
        val document = DocumentsContract.buildDocumentUri(AUTHORITY, "root")

        val failure = runCatching {
            GeneratedFilesArchiveSettingsStore.selectTree(context, document, "Nexus outputs")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == document })
        assertFalse(GeneratedFilesArchiveSettingsStore.read(context).configured)
    }

    @Test fun realPublishCreatesReadableProjectAndConversationFoldersAndAvoidsDuplicates() {
        val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        GeneratedFilesArchiveSettingsStore.selectTree(context, tree, "Nexus outputs")
        val first = File(context.cacheDir, "first-result.txt").apply { writeText("第一份") }
        val second = File(context.cacheDir, "second-result.txt").apply { writeText("第二份") }

        val publishedFirst = requireNotNull(GeneratedFilesArchive.publish(
            context = context,
            source = first,
            runId = "run-one",
            projectId = null,
            subjectType = null,
            subjectId = null,
            label = "result.txt",
        ))
        val publishedSecond = requireNotNull(GeneratedFilesArchive.publish(
            context = context,
            source = second,
            runId = "run-two",
            projectId = null,
            subjectType = null,
            subjectId = null,
            label = "result.txt",
        ))

        assertEquals("未归类会话/未命名会话/result.txt", publishedFirst.relativePath)
        assertEquals("未归类会话/未命名会话/result-2.txt", publishedSecond.relativePath)
        assertTrue(publishedFirst.uri.startsWith(tree.toString().substringBeforeLast('/')))
        assertArrayEquals("第一份".toByteArray(), provider.read("result.txt"))
        assertArrayEquals("第二份".toByteArray(), provider.read("result-2.txt"))
    }

    @Test fun verifiedArtifactLedgerRegistrationPublishesTheSameReadableFile() {
        val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        GeneratedFilesArchiveSettingsStore.selectTree(context, tree, "Nexus outputs")
        val source = TerminalPrivateStorage.workspace(context.filesDir).resolve("registered/artifact.txt").apply {
            parentFile!!.mkdirs()
            writeText("ledger artifact")
        }
        val artifactId = "artifact-ledger-publish"
        NexusArtifactLedger(context).record(ArtifactRecord(
            id = artifactId,
            runId = "run-ledger",
            path = "registered/artifact.txt",
            label = "artifact.txt",
            kind = "file",
            status = ArtifactStatus.VERIFIED,
            createdAt = 1L,
            verifiedAt = 1L,
            location = "workspace",
        ))

        val published = requireNotNull(GeneratedFilesPublicationStore.read(context, artifactId))
        assertEquals("未归类会话/未命名会话/artifact.txt", published.relativePath)
        assertArrayEquals(source.readBytes(), provider.read("artifact.txt"))
    }

    @Test fun deliverFileToolRegistersWorkspaceOutputAndReportsVisibleLocation() {
        val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        GeneratedFilesArchiveSettingsStore.selectTree(context, tree, "Nexus outputs")
        val source = TerminalPrivateStorage.workspace(context.filesDir).resolve("terminal-output/report.md").apply {
            parentFile!!.mkdirs()
            writeText("# Delivered report")
        }
        NexusExecutionStore(context).use { store ->
            val request = RunRequest(
                runId = RunId("deliver-tool-run"),
                source = RunSource.CHAT,
                mode = RunMode.CHAT,
                hostId = HostId("native-android"),
                budget = RunBudget(hardToolCalls = 1, hardDurationMs = 30_000),
            )
            val recorder = NexusExecutionRecorder(store, request)
            val controller = AgentRunController()
            recorder.bindController(controller)
            recorder.start()
            val result = RuntimeInvocationScope.withController(controller) {
                AgentLocalTools(
                    context = context,
                    logger = AndroidAgentLogger,
                    terminalToolsEnabled = { true },
                    rootAvailable = { false },
                ).use { tools ->
                    tools.execute(AgentModelClient.ToolCall(
                        id = "deliver-call",
                        name = "deliver_file",
                        argumentsJson = """{"path":"terminal-output/report.md"}""",
                    )).content
                }
            }
            val payload = JSONObject(result)
            assertTrue(payload.getBoolean("ok"))
        assertEquals("published", payload.getString("publication"))
            assertEquals("未归类会话/未命名会话/report.md", payload.getString("visible_path"))
            assertArrayEquals(source.readBytes(), provider.read("report.md"))
        }
    }

    @Test fun replayingAnArtifactDoesNotPublishAnotherCopy() {
        val tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        GeneratedFilesArchiveSettingsStore.selectTree(context, tree, "Nexus outputs")
        TerminalPrivateStorage.workspace(context.filesDir).resolve("registered/replay.txt").apply {
            parentFile!!.mkdirs()
            writeText("replay")
        }
        val record = ArtifactRecord(
            id = "artifact-replay-once", runId = "run-replay", path = "registered/replay.txt",
            label = "replay.txt", kind = "file", status = ArtifactStatus.VERIFIED,
            createdAt = 1L, verifiedAt = 1L, location = "workspace",
        )
        val ledger = NexusArtifactLedger(context)
        ledger.record(record)
        val first = requireNotNull(GeneratedFilesPublicationStore.read(context, record.id))
        ledger.record(record)

        assertEquals(first, GeneratedFilesPublicationStore.read(context, record.id))
        assertEquals(listOf("replay.txt"), provider.fileNames())
    }

    @Test fun deliverFileRegistersMeetingOutputWithoutExternalArchive() {
        val source = TerminalPrivateStorage.workspace(context.filesDir).resolve("terminal-output/meeting-report.md").apply {
            parentFile!!.mkdirs()
            writeText("# Meeting report")
        }
        NexusExecutionStore(context).use { store ->
            val request = RunRequest(
                runId = RunId("deliver-meeting-run"), source = RunSource.CHAT, mode = RunMode.CHAT,
                hostId = HostId("native-android"), budget = RunBudget(hardToolCalls = 1, hardDurationMs = 30_000),
            )
            val recorder = NexusExecutionRecorder(store, request)
            val controller = AgentRunController()
            recorder.bindController(controller)
            recorder.start()
            val result = RuntimeInvocationScope.withController(controller) {
                AgentLocalTools(context = context, logger = AndroidAgentLogger, terminalToolsEnabled = { true },
                    rootAvailable = { false }, artifactSubjectType = "meeting", artifactSubjectId = "room-1").use { tools ->
                    tools.execute(AgentModelClient.ToolCall("meeting-call", "deliver_file",
                        JSONObject().put("path", "terminal-output/meeting-report.md").toString())).content
                }
            }
            val payload = JSONObject(result)
            assertTrue(payload.getBoolean("ok"))
            assertEquals("not_configured", payload.getString("publication"))
            val records = NexusArtifactLedger(context).forSubject("meeting", "room-1")
            assertEquals(payload.getString("artifact_id"), records.single().id)
            assertEquals(source.readText(), kotlinx.coroutines.runBlocking { NexusArtifactAccess(context).readText(records.single().id) })
            assertTrue(NexusArtifactLedger(context).forSubject("meeting", "room-2").isEmpty())
        }
    }

    private companion object { const val AUTHORITY = "eta.generated-files.test" }
}

private class GeneratedFilesTestDocumentsProvider : DocumentsProvider() {
    private data class Node(val id: String, val parentId: String?, var name: String, val directory: Boolean, val file: File?)
    private val nodes = linkedMapOf("root" to Node("root", null, "Nexus outputs", true, null))
    private var sequence = 0

    fun read(name: String): ByteArray = nodes.values.single { it.name == name && !it.directory }.file!!.readBytes()
    fun fileNames(): List<String> = nodes.values.filterNot { it.directory }.map { it.name }

    override fun onCreate() = true

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        requireNotNull(nodes[parentDocumentId]).also { require(it.directory) }
        val id = "document-${++sequence}"
        val directory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        nodes[id] = Node(
            id = id,
            parentId = parentDocumentId,
            name = displayName,
            directory = directory,
            file = if (directory) null else File.createTempFile("generated-", ".bin", requireNotNull(context).cacheDir),
        )
        return id
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        requireNotNull(nodes[documentId]).name = displayName
        return documentId
    }

    override fun deleteDocument(documentId: String) {
        requireNotNull(nodes.remove(documentId)).file?.delete()
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_TITLE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    DocumentsContract.Root.COLUMN_ROOT_ID -> "root"
                    DocumentsContract.Root.COLUMN_TITLE -> "Nexus outputs"
                    else -> null
                }
            })
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        rows(listOf(documentId), projection)

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        rows(nodes.values.filter { it.parentId == parentDocumentId }.map { it.id }, projection)

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var current = nodes[documentId]?.parentId
        while (current != null) {
            if (current == parentDocumentId) return true
            current = nodes[current]?.parentId
        }
        return false
    }

    private fun rows(ids: List<String>, projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return MatrixCursor(columns).apply {
            ids.forEach { id ->
                val node = nodes[id] ?: return@forEach
                addRow(columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> if (node.directory) {
                            DocumentsContract.Document.MIME_TYPE_DIR
                        } else "text/plain"
                        DocumentsContract.Document.COLUMN_SIZE -> node.file?.length()
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> node.file?.lastModified()
                        DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                        else -> null
                    }
                })
            }
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = nodes[documentId]?.file ?: throw FileNotFoundException(documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }
}
