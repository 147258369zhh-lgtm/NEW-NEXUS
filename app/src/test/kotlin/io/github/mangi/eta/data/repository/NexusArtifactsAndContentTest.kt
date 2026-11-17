package io.github.mangi.eta.data.repository

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.agent.terminal.FileToolDispatcher
import io.github.mangi.eta.agent.terminal.LocalFileToolBackend
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.app.AgentConversationStore
import io.github.mangi.eta.ui.app.AgentRunMessageProjector
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.zhh_lgtm.nexus.application.api.ArtifactRecord
import io.github.zhh_lgtm.nexus.application.api.ArtifactStatus
import io.github.zhh_lgtm.nexus.kernel.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.ByteArrayOutputStream
import android.net.Uri
import java.util.Collections
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NexusArtifactsAndContentTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @After fun close() { EtaDatabase.closeForTests() }

    @Test fun actualFileReceiptRegistersOneStableArtifactForRunConversationAndProject() {
        val workspace = TerminalPrivateStorage.workspace(context.filesDir).apply { mkdirs() }
        val backend = LocalFileToolBackend(workspace)
        val args = JSONObject().put("path", "reports/result.md").put("content", "actual file body")
        val call = AgentModelClient.ToolCall("write-1", "write_file", args.toString())
        val raw = FileToolDispatcher.execute(backend, call.name, args)
        assertTrue(JSONObject(raw).getBoolean("ok"))
        NexusExecutionStore(context).use { store ->
            val request = RunRequest(RunId("file-run"), RunSource.CHAT, RunMode.CHAT, HostId("native-android"), projectId = "project-1")
            val recorder = NexusExecutionRecorder(store, request); val controller = AgentRunController()
            recorder.bindController(controller); recorder.start()
            val first = RuntimeInvocationScope.withController(controller) { RunArtifactCapture.fileResult(context, call, raw, "conversation", "chat-1") }
            val second = RuntimeInvocationScope.withController(controller) { RunArtifactCapture.fileResult(context, call, raw, "conversation", "chat-1") }
            val id = JSONObject(first).getString("artifact_id")
            assertEquals(id, JSONObject(second).getString("artifact_id"))
            val ledger = NexusArtifactLedger(context)
            val found = ledger.find(id)!!
            assertEquals(id, ledger.forSubject("conversation", "chat-1").single().id)
            assertEquals(id, ledger.forProject("project-1").single().id)
            assertEquals("reports/result.md", found.path)
            assertEquals("workspace", found.location)
            assertEquals(ArtifactStatus.VERIFIED, found.status)
            assertTrue(NexusArtifactAccess(context).readableUri(id)!!.startsWith("file:"))
            assertEquals("actual file body", File(workspace, found.path).readText())
            assertFalse(NexusArtifactAccess(context).readableUri("missing") != null)
            recorder.record(AgentEvent.ToolStarted(1, call.id, call.name, "fixture"))
            recorder.record(AgentEvent.ToolFinished(1, call.id, call.name, "done", 0, 0, true, artifactId = id, artifactName = "result.md"))
            assertEquals("nexus-artifact://$id", store.timeline(request.runId).last().event.payloadRef)
            recorder.finish(AgentRuntimeWire.RunResult("file-run", true, "done"), false)
        }
    }

    @Test fun meetingFileReceiptsRegisterFilesButNotDirectories() {
        val workspace = TerminalPrivateStorage.workspace(context.filesDir).apply { mkdirs() }
        val backend = LocalFileToolBackend(workspace)
        val source = File(workspace, "meeting-receipts/source.txt").apply { parentFile!!.mkdirs(); writeText("report") }
        NexusExecutionStore(context).use { store ->
            val request = RunRequest(RunId("meeting-receipts-run"), RunSource.CHAT, RunMode.CHAT, HostId("native-android"))
            val recorder = NexusExecutionRecorder(store, request); val controller = AgentRunController()
            recorder.bindController(controller); recorder.start()
            fun capture(name: String, id: String, args: JSONObject): JSONObject {
                val call = AgentModelClient.ToolCall(id, name, args.toString())
                val raw = FileToolDispatcher.execute(backend, name, args)
                assertTrue(JSONObject(raw).getBoolean("ok"))
                return JSONObject(RuntimeInvocationScope.withController(controller) {
                    RunArtifactCapture.fileResult(context, call, raw, "meeting", "meeting-receipts")
                })
            }
            val directory = capture("make_directory", "directory", JSONObject().put("path", "meeting-receipts/folder"))
            assertFalse(directory.has("artifact_id"))
            val copied = capture("copy_file", "copy", JSONObject().put("source", source.path)
                .put("destination", "meeting-receipts/copied.txt"))
            val records = NexusArtifactLedger(context).forSubject("meeting", "meeting-receipts")
            assertEquals(copied.getString("artifact_id"), records.single().id)
            assertEquals("report", File(workspace, records.single().path).readText())
            assertTrue(NexusArtifactLedger(context).forSubject("meeting", "other-meeting").isEmpty())
        }
    }

    @Test fun distinctLedgerInstancesCannotLoseParallelRecordsAndKeepCallerAssignedIds() {
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val workers = (1..4).map { worker -> thread {
            try { repeat(8) { item ->
                NexusArtifactLedger(context).record(ArtifactRecord("file-$worker-$item", "run-$worker", "file-$worker-$item.txt",
                    "file", "file", ArtifactStatus.PENDING, item.toLong()))
            } } catch (failure: Throwable) { errors += failure }
        } }
        workers.forEach { it.join(5_000); assertFalse(it.isAlive) }
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(32, NexusArtifactLedger(context).list(100).size)
        assertNotNull(NexusArtifactLedger(context).find("file-3-4"))
        assertThrows(IllegalArgumentException::class.java) {
            NexusArtifactLedger(context).record(ArtifactRecord("file-3-4", "another-run", "other.txt", "other", "file", ArtifactStatus.PENDING, 0))
        }
    }

    @Test fun replayCannotTransferAnExistingArtifactToAnotherMeetingOrProject() {
        val store = WorkspaceArtifactStore(context)
        store.record("run", "result.txt", id = "stable-id", subjectType = "meeting", subjectId = "meeting-a", projectId = "project-a")
        assertThrows(IllegalArgumentException::class.java) {
            store.record("run", "result.txt", id = "stable-id", subjectType = "meeting", subjectId = "meeting-b", projectId = "project-a")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.record("run", "result.txt", id = "stable-id", subjectType = "meeting", subjectId = "meeting-a", projectId = "project-b")
        }
        assertEquals("stable-id", store.forSubject("meeting", "meeting-a").single().id)
        assertTrue(store.forSubject("meeting", "meeting-b").isEmpty())
        assertTrue(store.forProject("project-b").isEmpty())
    }

    @Test fun verifiedArtifactExportsExactBytesToTheUserSelectedDocument() = runBlocking(Dispatchers.IO) {
        val workspace = TerminalPrivateStorage.workspace(context.filesDir).apply { mkdirs() }
        val file = File(workspace, "media/clip.mp4").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(0, 1, 2, 127)) }
        val id = "artifact-export"
        NexusArtifactLedger(context).record(ArtifactRecord(id, "export-run", "media/clip.mp4", file.name, "video",
            ArtifactStatus.VERIFIED, System.currentTimeMillis(), mediaType = "video/mp4", sizeBytes = file.length()))
        val destination = Uri.parse("content://nexus.test/export.mp4")
        val output = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(destination, output)
        NexusArtifactAccess(context).exportTo(id, destination)
        assertArrayEquals(file.readBytes(), output.toByteArray())
        assertTrue(runCatching {
            runBlocking { NexusArtifactAccess(context).exportTo("missing", destination) }
        }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun structuredTextCodeMathMediaAndReferencesSurviveCodecAndActualConversationPersistence() = runBlocking(Dispatchers.IO) {
        val body = "Saved **answer**"
        val parts: List<ContentPart> = listOf(MarkdownPart("body", body), CodePart("code", "kotlin", "x".repeat(40_000)),
            MathPart("math", "a^2+b^2=c^2"), FilePart("file", ArtifactId("file-1"), "Report"),
            ImagePart("image", ArtifactId("image-1"), "image/png"), AudioPart("audio", ArtifactId("audio-1"), "audio/wav", 1000),
            VideoPart("video", ArtifactId("video-1"), "video/mp4", 2000, 1280, 720),
            ToolTracePart("tool", ItemId("tool-1")), PlanCardPart("plan", PlanId("plan-1")),
            UserInputPart("input", "input-1", "Question", listOf("a", "b"), true), ApprovalPart("approval", "approval-1"))
        assertEquals(parts, NexusContentParts.decode(NexusContentParts.encode(parts, body), "answer", body))
        val state = AgentChatHomeUiState(messages = listOf(AgentMessageUi("answer", body, contentParts = parts)),
            input = "", isStreaming = false, thinkingEnabled = false)
        AgentConversationStore.save(context, "chat-1", mapOf("chat-1" to state), mapOf("chat-1" to "Fixture"), mapOf("chat-1" to 1L))
        EtaDatabase.closeForTests()
        val restored = AgentConversationStore.loadConversation(context, "chat-1")!!.messages.filterIsInstance<AgentMessageUi>().single()
        assertEquals(body, restored.content)
        assertEquals(parts, restored.contentParts)
        val stored = EtaDatabase.get(context).conversationDao().messageRow("chat-1", "answer")!!
        assertTrue(stored.contentPartsJson.startsWith(io.github.mangi.eta.data.db.ChunkedTextDao.REFERENCE_PREFIX))
    }

    @Test fun malformedFutureContentKeepsLegacyTextAndFinishedToolProducesOpenableFilePartOnce() {
        val fallback = NexusContentParts.decode("{\"version\":99}", "legacy", "original text")
        assertEquals(listOf(MarkdownPart("legacy:body", "original text")), fallback)
        val projector = AgentRunMessageProjector()
        val started = projector.applyEvent("run-1", AgentEvent.ToolStarted(1, "write", "write_file", "write"), emptyList())
        val event = AgentEvent.ToolFinished(1, "write", "write_file", "done", 0, 0, true,
            artifactId = "artifact-1", artifactName = "Report.md")
        val projected = projector.applyEvent("run-1", event, started)
        val files = projected.filterIsInstance<AgentMessageUi>().single().contentParts
        assertTrue(files.single() is FilePart)
        assertEquals(projected, projector.applyEvent("run-1", event, projected))
        assertEquals(event, AgentRuntimeWire.eventFromBundle(AgentRuntimeWire.eventToBundle(event)))
        assertEquals(event, AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(event)))
    }

    @Test fun registeringFailedOrUntrustedTextCannotPretendToProduceAFile() {
        val call = AgentModelClient.ToolCall("fixture", "write_file", "{}")
        assertEquals("{\"ok\":false}", RunArtifactCapture.fileResult(context, call, "{\"ok\":false}", "conversation", "chat"))
        assertTrue(NexusArtifactLedger(context).list().isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceArtifactStore(context).record("run", "../outside", location = "workspace")
        }
    }
}
