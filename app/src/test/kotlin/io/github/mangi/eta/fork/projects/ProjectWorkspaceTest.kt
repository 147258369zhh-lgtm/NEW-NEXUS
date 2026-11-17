package io.github.mangi.eta.fork.projects

import android.Manifest
import android.app.Application
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.data.repository.NexusArtifactLedger
import io.github.mangi.eta.data.repository.RunArtifactCapture
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.zhh_lgtm.nexus.kernel.api.HostId
import io.github.zhh_lgtm.nexus.kernel.api.RunId
import io.github.zhh_lgtm.nexus.kernel.api.RunMode
import io.github.zhh_lgtm.nexus.kernel.api.RunRequest
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AgentLogger
import java.io.File
import org.json.JSONObject
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
class ProjectWorkspaceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: ProjectTestDocumentsProvider
    private val treeUri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
    private val project get() = ProjectEntity("project-fixture", "Fixture", "app", treeUri.toString(), "Fixture", "SAF", 1, 1)

    @Before fun setup() {
        Prefs.initLocal(context)
        provider = ProjectTestDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply {
            authority = AUTHORITY; exported = true; grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS; writePermission = Manifest.permission.MANAGE_DOCUMENTS
        })
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }
    private fun workspace(run: String = "run-1", available: () -> Boolean = { true }) = ProjectWorkspace.open(context, project, run,
        SafProjectTree(context, treeUri) { _, _ -> true }, available)

    @Test fun malformedWorkCopiesDoNotHideValidSavedCopy() {
        val valid = workspace("valid-listing").apply { ensurePrepared() }
        val projectRoot = File(valid.binding.hostPath).parentFile!!.parentFile!!
        fun broken(name: String, manifest: String) {
            val root = File(projectRoot, name)
            File(root, "files").mkdirs()
            File(root, "binding.json").writeText(manifest)
        }
        broken("missing-run", JSONObject().put("project_id", project.id).toString())
        broken("wrong-type", JSONObject().put("project_id", project.id).put("run_id", 42).toString())
        broken("bad-json", "{")
        broken(".preparing-copy", JSONObject().put("project_id", project.id).put("run_id", "incomplete").toString())
        val copies = ProjectWorkspaceRepository.copies(context, project.id)
        assertEquals(listOf("valid-listing"), copies.map { it.runId })
    }

    @Test fun actualAgentFileToolsOperateOnTheBoundCopyAndPublishOnlySelectedVerifiedFiles() {
        provider.seed("docs/source.txt", "original")
        val workspace = workspace()
        assertFalse(File(workspace.binding.hostPath).exists())
        AgentLocalTools(context, Logger, terminalToolsEnabled = { true }, rootAvailable = { false }, projectWorkspace = workspace).use { tools ->
            val read = JSONObject(tools.execute(AgentModelClient.ToolCall("read", "read_file", "{\"path\":\"docs/source.txt\"}")).content)
            assertTrue(read.toString(), read.getBoolean("ok")); assertEquals("original", read.getString("content"))
            assertEquals(workspace.binding.hostPath, tools.terminalExecutionContext.androidWorkspacePath)
            assertEquals(workspace.binding.hostPath, tools.terminalExecutionContext.linuxHostWorkspacePath)
            val changed = tools.execute(AgentModelClient.ToolCall("edit", "write_file", "{\"path\":\"docs/source.txt\",\"content\":\"revised\"}"))
            assertTrue(changed.content, JSONObject(changed.content).getBoolean("ok"))
            tools.execute(AgentModelClient.ToolCall("new", "write_file", "{\"path\":\"reports/new.txt\",\"content\":\"report\"}"))
            assertEquals("original", provider.read("docs/source.txt"))
            val status = JSONObject(tools.execute(AgentModelClient.ToolCall("status", "project_workspace_status", "{}")).content)
            assertTrue(status.getBoolean("source_read_only")); assertEquals(2, status.getInt("total_changes"))
            val args = JSONObject().put("paths", org.json.JSONArray(listOf("docs/source.txt"))).put("change_fingerprint", status.getString("change_fingerprint"))
            val publish = tools.execute(AgentModelClient.ToolCall("publish", "project_workspace_publish", args.toString()))
            assertTrue(publish.content, JSONObject(publish.content).getBoolean("ok"))
            assertEquals("revised", provider.read("docs/source.txt")); assertFalse(provider.exists("reports/new.txt"))
            val repeated = tools.execute(AgentModelClient.ToolCall("publish", "project_workspace_publish", args.toString()))
            assertEquals(publish.content, repeated.content)
            assertEquals(1, workspace.status().getInt("total_changes"))
        }
    }

    @Test fun actualProjectFileReceiptIsRegisteredOnlyForItsMeeting() {
        val workspace = workspace("meeting-capture")
        val controller = AgentRunController().apply {
            bindExecutionIdentity(RunRequest(RunId("meeting-capture"), RunSource.MEETING_PARTICIPANT,
                RunMode.MEETING_PARTICIPANT, HostId("native-android"), projectId = project.id))
        }
        AgentLocalTools(context, Logger, terminalToolsEnabled = { true }, rootAvailable = { false },
            projectWorkspace = workspace, artifactSubjectType = "meeting", artifactSubjectId = "meeting-a").use { tools ->
            RuntimeInvocationScope.withController(controller) {
                val call = AgentModelClient.ToolCall("meeting-write", "write_file",
                    JSONObject().put("path", "reports/result.txt").put("content", "meeting result").toString())
                val result = JSONObject(tools.execute(call).content)
                assertTrue(result.toString(), result.getBoolean("ok"))
                val ledger = NexusArtifactLedger(context)
                val record = ledger.forSubject("meeting", "meeting-a").single()
                assertEquals(result.getString("artifact_id"), record.id)
                assertEquals(project.id, record.projectId)
                assertEquals("meeting result", File(TerminalPrivateStorage.workspace(context.filesDir), record.path).readText())
                assertTrue(ledger.forSubject("meeting", "meeting-b").isEmpty())

                val failed = JSONObject(tools.execute(AgentModelClient.ToolCall("failed-edit", "edit_file",
                    JSONObject().put("path", "missing.txt").put("old_text", "x").put("new_text", "y").toString())).content)
                assertFalse(failed.getBoolean("ok"))
                assertFalse(failed.has("artifact_id"))
                assertEquals(1, ledger.forSubject("meeting", "meeting-a").size)
            }
        }
    }

    @Test fun linuxWorkspaceReceiptRejectsMissingAndOutsideFiles() {
        val workspace = workspace("linux-meeting-capture").apply { ensurePrepared() }
        val controller = AgentRunController().apply {
            bindExecutionIdentity(RunRequest(RunId("linux-meeting-capture"), RunSource.MEETING_PARTICIPANT,
                RunMode.MEETING_PARTICIPANT, HostId("native-android"), projectId = project.id))
        }
        val file = File(workspace.binding.hostPath, "result.txt").apply { writeText("linux result") }
        val call = AgentModelClient.ToolCall("linux-write", "write_file", "{}")
        val mount = "/workspace" to workspace.binding.hostPath
        RuntimeInvocationScope.withController(controller) {
            fun capture(path: String, id: String) = JSONObject(RunArtifactCapture.fileResult(context,
                call.copy(id = id), JSONObject().put("ok", true).put("path", path).toString(), "meeting", "linux-meeting", mount))
            val receipt = capture("/workspace/result.txt", "good")
            val ledger = NexusArtifactLedger(context)
            val record = ledger.forSubject("meeting", "linux-meeting").single()
            assertEquals(receipt.getString("artifact_id"), record.id)
            assertEquals(file.canonicalFile, File(TerminalPrivateStorage.workspace(context.filesDir), record.path).canonicalFile)
            assertEquals("requires_deliver_file", capture("/workspace/missing.txt", "missing").getString("artifact_registration"))
            assertEquals("requires_deliver_file", capture("/workspace/../outside.txt", "outside").getString("artifact_registration"))
            assertEquals(1, ledger.forSubject("meeting", "linux-meeting").size)
            assertTrue(ledger.forSubject("meeting", "other-meeting").isEmpty())
        }
    }

    @Test fun staleOriginalAndStaleWorkCopyAreRejectedBeforeWritingSourceData() {
        provider.seed("source.txt", "original")
        val workspace = workspace().apply { ensurePrepared() }
        val copy = File(workspace.binding.hostPath, "source.txt")
        copy.writeText("revised")
        val status = workspace.status()
        provider.replace("source.txt", "changed elsewhere")
        val writes = provider.writeOpens
        assertThrows(IllegalArgumentException::class.java) { workspace.publish(listOf("source.txt"), status.getString("change_fingerprint"), "conflict") }
        assertEquals(writes, provider.writeOpens); assertEquals("changed elsewhere", provider.read("source.txt"))
        provider.replace("source.txt", "original")
        copy.writeText("changed after preview")
        assertThrows(IllegalArgumentException::class.java) { workspace.publish(listOf("source.txt"), status.getString("change_fingerprint"), "stale-copy") }
        assertEquals(writes, provider.writeOpens)
    }

    @Test fun failedCommitRestoresOriginalNameAndKeepsCompletedReceiptsWithoutReplaying() {
        provider.seed("first.txt", "one"); provider.seed("second.txt", "two")
        val workspace = workspace().apply { ensurePrepared() }
        File(workspace.binding.hostPath, "first.txt").writeText("ONE")
        File(workspace.binding.hostPath, "second.txt").writeText("TWO")
        provider.failFinalRename = "second.txt"
        val fingerprint = workspace.status().getString("change_fingerprint")
        val result = workspace.publish(listOf("first.txt", "second.txt"), fingerprint, "partial-publish")
        assertFalse(result.getBoolean("ok")); assertEquals(listOf("first.txt"), (0 until result.getJSONArray("published").length()).map { result.getJSONArray("published").getString(it) })
        assertEquals("ONE", provider.read("first.txt")); assertEquals("two", provider.read("second.txt"))
        assertEquals(0, result.getJSONArray("retained_backups").length())
        val operations = provider.writeOpens
        provider.failFinalRename = null
        assertEquals(result.toString(), workspace.publish(listOf("first.txt", "second.txt"), fingerprint, "partial-publish").toString())
        assertEquals(operations, provider.writeOpens)
        val after = workspace.status()
        assertTrue(workspace.publish(listOf("second.txt"), after.getString("change_fingerprint"), "continue-after-review").getBoolean("ok"))
        assertEquals("TWO", provider.read("second.txt"))
    }

    @Test fun providerFailureWhileRestoringOldNameRetainsBackupForManualRecovery() {
        provider.seed("source.txt", "original")
        val workspace = workspace().apply { ensurePrepared() }
        File(workspace.binding.hostPath, "source.txt").writeText("revised")
        provider.failFinalRename = "source.txt"
        provider.failBackupRestore = true
        provider.failBackupRestoreName = "source.txt"

        val result = workspace.publish(
            listOf("source.txt"), workspace.status().getString("change_fingerprint"), "restore-failure",
        )

        assertFalse(result.getBoolean("ok"))
        assertEquals("PROJECT_PUBLISH_INCOMPLETE", result.getString("code"))
        assertFalse(provider.exists("source.txt"))
        val retained = result.getJSONArray("retained_backups")
        assertEquals(1, retained.length())
        val backup = retained.getJSONObject(0)
        assertEquals("source.txt", backup.getString("path"))
        assertEquals("original", provider.read(backup.getString("backup_name")))
        val history = workspace.status().getJSONArray("publish_history").getJSONObject(0)
        assertEquals("INCOMPLETE", history.getString("phase"))
        assertEquals("source.txt", history.getJSONArray("retained_backups").getJSONObject(0).getString("path"))
        provider.failBackupRestore = false
        assertEquals(result.toString(), workspace.publish(
            listOf("source.txt"), workspace.status().getString("change_fingerprint"), "restore-failure",
        ).toString())
        assertEquals("original", provider.read(backup.getString("backup_name")))
    }

    @Test fun permissionsArchiveCancellationAndDeletedPathsDoNotWriteOriginals() {
        provider.seed("source.txt", "original")
        assertThrows(IllegalStateException::class.java) { ProjectWorkspace.prepare(context, project, "no-grant", SafProjectTree(context, treeUri) { _, _ -> false }) }
        var active = true
        val workspace = workspace(available = { active }).apply { ensurePrepared() }
        active = false
        assertThrows(IllegalStateException::class.java) { workspace.status() }
        active = true
        File(workspace.binding.hostPath, "source.txt").delete()
        val state = workspace.status()
        assertEquals("deleted", state.getJSONArray("changes").getJSONObject(0).getString("kind"))
        assertThrows(IllegalArgumentException::class.java) { workspace.publish(listOf("source.txt"), state.getString("change_fingerprint"), "delete") }
        assertEquals("original", provider.read("source.txt"))
        val controller = AgentRunController().apply { cancel() }
        assertTrue(runCatching { RuntimeInvocationScope.withController(controller) { workspace.status() } }.isFailure)
    }

    @Test fun copyQuotaFailureDoesNotLeaveAPartialWorkspaceOrTouchTheOriginal() {
        provider.seed("large.bin", "original")
        provider.reportedSize = ProjectWorkspace.MAX_FILE_BYTES + 1
        val workspace = workspace()
        assertThrows(IllegalArgumentException::class.java) { workspace.ensurePrepared() }
        assertFalse(File(workspace.binding.hostPath).exists())
        assertEquals("original", provider.read("large.bin"))
    }

    @Test fun linuxLaunchersBindTheActualProjectCopyInsteadOfTheSharedGlobalFolder() {
        val path = "/data/user/0/io.github.zhh_lgtm.nexus/files/project 'copy"
        val payload = ShellProcessSupervisor(workspacePath = path).buildLinuxPayload("/private/rootfs", "pwd")
        assertTrue(payload.contains("project")); assertFalse(payload.contains("eta_mount_required /data/local/tmp/eta "))
        assertTrue(payload.contains("eta_rootfs/workspace"))
        val workspace = workspace()
        val terminal = TerminalExecutionContext(TerminalEnvironment.ALPINE, "/rootfs", userWorkspacePath = workspace.binding.hostPath, workspaceBinding = workspace.binding)
        assertEquals(workspace.binding.hostPath, terminal.linuxHostWorkspacePath)
        assertTrue(terminal.description(false).contains("project_workspace_publish"))
        val supervisor = io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor(Logger, File(context.cacheDir, "fixture-daemons.json"), workspacePath = workspace.binding.hostPath)
        val host = supervisor.hostWorkspaceFor(TerminalEnvironment.DEBIAN, "root")
        assertEquals(workspace.binding.hostPath, host)
        val task = io.github.mangi.eta.agent.terminal.DetachedTask("daemon", 1, "token", "fixture", "/workspace", "root",
            TerminalEnvironment.DEBIAN, "/workspace/daemon/fixture.log", 1, hostWorkspace = host)
        assertEquals("${workspace.binding.hostPath}/daemon/fixture.log", supervisor.hostDaemonPath(task, task.logPath))
    }
    private object Logger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
    companion object { const val AUTHORITY = "nexus.project.test.documents" }
}

class ProjectTestDocumentsProvider : DocumentsProvider() {
    private lateinit var directory: File
    var writeOpens = 0
    var failFinalRename: String? = null
    var failBackupRestore = false
    var failBackupRestoreName: String? = null
    var reportedSize: Long? = null
    override fun onCreate(): Boolean { directory = File(requireNotNull(context).cacheDir, "project-source").apply { mkdirs() }; return true }
    fun seed(path: String, value: String) { File(directory, path).apply { parentFile!!.mkdirs(); writeText(value) } }
    fun read(path: String) = File(directory, path).readText()
    fun replace(path: String, value: String) { File(directory, path).writeText(value) }
    fun exists(path: String) = File(directory, path).exists()
    private fun file(id: String) = if (id == "root") directory else File(directory, id.removePrefix("doc:"))
    private fun id(file: File) = if (file == directory) "root" else "doc:" + file.relativeTo(directory).invariantSeparatorsPath
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = rows(listOf(file(documentId)).filter(File::exists), projection)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = rows(file(parentDocumentId).listFiles().orEmpty().toList(), projection)
    override fun isChildDocument(parentDocumentId: String, documentId: String) = file(documentId).canonicalFile.toPath().startsWith(file(parentDocumentId).canonicalFile.toPath())
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val created = File(file(parentDocumentId), displayName)
        check(if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) created.mkdir() else created.createNewFile())
        return id(created)
    }
    override fun renameDocument(documentId: String, displayName: String): String {
        val source = file(documentId)
        if (displayName == failFinalRename && source.name.endsWith(".part")) throw java.io.IOException("fixture publish failed")
        if (failBackupRestore && displayName == failBackupRestoreName) {
            throw java.io.IOException("fixture restore failed")
        }
        val destination = File(source.parentFile, displayName)
        check(!destination.exists() && source.renameTo(destination))
        return id(destination)
    }
    override fun deleteDocument(documentId: String) { check(file(documentId).delete()) }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode != "r") writeOpens++
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    }
    private fun rows(files: List<File>, projection: Array<out String>?): Cursor {
        val columns = requireNotNull(projection)
        return MatrixCursor(columns).apply { files.forEach { file -> addRow(columns.map { column -> when (column) {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id(file)
            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
            DocumentsContract.Document.COLUMN_MIME_TYPE -> if (file.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
            DocumentsContract.Document.COLUMN_SIZE -> if (file.isDirectory) null else reportedSize ?: file.length()
            DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
            DocumentsContract.Document.COLUMN_FLAGS -> DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
            else -> null
        } }) } }
    }
}
