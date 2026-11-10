package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.agent.runtime.AgentMetricsDatabase
import io.github.mangi.eta.agent.runtime.AgentRunMetricsStore
import io.github.mangi.eta.agent.runtime.AgentRunTelemetry
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.fork.a2a.A2ADatabase
import io.github.mangi.eta.fork.conversations.ConversationExtensionDatabase
import io.github.mangi.eta.fork.conversations.ConversationExtensionRepository
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationBranchEntity
import io.github.mangi.eta.data.db.ConversationBranchSetupEntity
import io.github.mangi.eta.fork.projects.ProjectDatabase
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.mangi.eta.fork.projects.ProjectDraft
import io.github.mangi.eta.fork.projects.ProjectSubjectType
import kotlinx.coroutines.Dispatchers
import io.github.mangi.eta.fork.learning.LearningDatabase
import io.github.mangi.eta.fork.meeting.MeetingDatabase
import io.github.mangi.eta.fork.meeting.MeetingParticipantDraft
import io.github.mangi.eta.fork.meeting.MeetingRepository
import io.github.mangi.eta.fork.meeting.MeetingRoomStatus
import io.github.mangi.eta.fork.meeting.MeetingToolActivity
import io.github.mangi.eta.fork.meeting.MeetingToolPhase
import io.github.mangi.eta.fork.meeting.MeetingStreamActivity
import io.github.mangi.eta.fork.meeting.MeetingStreamPhase
import io.github.mangi.eta.fork.meeting.toUiState
import io.github.mangi.eta.ui.screens.meeting.conversationTimeline
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import kotlinx.coroutines.flow.first
import io.github.mangi.eta.fork.tasks.AgentTaskDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EtaFullBackupArchiveTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        AgentRunMetricsStore.flush()
        closeDatabases()
        context.deleteDatabase(AgentMetricsDatabase.NAME)
        context.deleteDatabase("eta.db")
        context.deleteDatabase("eta_meeting.db")
        context.deleteDatabase("eta_fork.db")
        context.deleteDatabase("eta_learning.db")
        context.deleteDatabase("eta_task.db")
        context.deleteDatabase("eta_a2a.db")
        context.deleteDatabase("eta_projects.db")
        File(context.dataDir, "eta_restore_control").deleteRecursively()
        resetPortableSecretFixtures()
        SettingsDataStore.init(context)
        runBlocking { SettingsDataStore.setSelection(null, null) }
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
    }

    @After
    fun tearDown() {
        File(context.dataDir, "eta_restore_control").deleteRecursively()
        resetPortableSecretFixtures()
        closeDatabases()
    }

    @Test
    fun inspectRejectsNewerRawDatabaseBeforeAnyRestoreIsStaged() = runBlocking {
        val database = EtaDatabase.get(context).openHelper.writableDatabase
        database.execSQL("PRAGMA user_version = 99")
        val output = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, output)
        database.execSQL("PRAGMA user_version = 26")
        val result = runCatching { EtaFullBackupArchive.inspect(context, ByteArrayInputStream(output.toByteArray())) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("版本不兼容"))
        assertFalse(EtaFullBackupArchive.hasPendingRestore(context))
    }

    @Test
    fun workManagerFilesAreNotMigratedAndPreviewClearlyIdentifiesFullArchives() = runBlocking {
        val work = File(context.noBackupFilesDir, "androidx.work.workdb").apply { parentFile!!.mkdirs(); writeText("local scheduler") }
        context.getSharedPreferences("eta_auto_backup_secret", Context.MODE_PRIVATE).edit()
            .putString("ciphertext", "device-bound-fixture")
            .commit()
        val output = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, output)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip -> while (true) { val entry = zip.nextEntry ?: break; names += entry.name } }
        assertTrue(names.none { it.contains("androidx.work.workdb") })
        assertFalse("data/shared_prefs/eta_auto_backup_secret.xml" in names)
        val preview = EtaFullBackupArchive.inspect(context, ByteArrayInputStream(output.toByteArray()))
        assertTrue(preview.fullArchive)
        assertFalse(preview.restartRequired)
        assertFalse(EtaFullBackupArchive.hasPendingRestore(context))
        work.delete()
        context.getSharedPreferences("eta_auto_backup_secret", Context.MODE_PRIVATE).edit().clear().commit()
        Unit
    }

    @Test
    fun fastAppDataArchiveOmitsFilesAndRestoreKeepsExistingWorkspaceFiles() = runBlocking {
        val keep = File(context.filesDir, "workspace/keep-after-restore.txt").apply {
            parentFile?.mkdirs()
            writeText("keep me")
        }
        val archive = ByteArrayOutputStream()
        EtaFullBackupArchive.export(
            context = context,
            output = archive,
            includeAppFiles = false,
            compression = EtaBackupCompression.FAST,
        )

        val names = buildSet {
            ZipInputStream(ByteArrayInputStream(archive.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    add(entry.name)
                }
            }
        }
        assertFalse("data/files/workspace/keep-after-restore.txt" in names)
        assertFalse(names.any { it.startsWith("data/files/") })

        val summary = EtaFullBackupArchive.import(context, ByteArrayInputStream(archive.toByteArray()))
        assertTrue(summary.restartRequired)
        closeDatabases()
        assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
        assertEquals("keep me", keep.readText())
    }

    @Test
    fun encryptedContainerRoundTripsFramesAndRejectsWrongPassword() {
        val original = ByteArray(2 * 1024 * 1024 + 17) { index -> (index * 31).toByte() }
        val encrypted = ByteArrayOutputStream()
        EtaBackupEncryptedOutputStream(encrypted, "correct horse battery staple").use { output ->
            output.write(original)
        }

        val restored = EtaBackupEncryptedInputStream(
            ByteArrayInputStream(encrypted.toByteArray()),
            "correct horse battery staple",
        ).use(InputStreamCopy::copy)
        assertTrue(original.contentEquals(restored))

        val failure = runCatching {
            EtaBackupEncryptedInputStream(
                ByteArrayInputStream(encrypted.toByteArray()),
                "wrong password",
            ).use(InputStreamCopy::copy)
        }.exceptionOrNull()
        assertTrue(failure is EtaBackupException)
    }

    @Test
    fun encryptedContainerCoalescesSmallWritesIntoBoundedFrames() {
        val original = ByteArray(4096) { (it % 251).toByte() }
        val encrypted = ByteArrayOutputStream()
        EtaBackupEncryptedOutputStream(encrypted, "password").use { output ->
            original.forEach { output.write(it.toInt() and 0xff) }
        }
        assertTrue("Small ZIP writes must not each allocate an encrypted frame", encrypted.size() < original.size + 1024)
        val restored = EtaBackupEncryptedInputStream(ByteArrayInputStream(encrypted.toByteArray()), "password").use { it.readBytes() }
        assertTrue(original.contentEquals(restored))
    }

    @Test
    fun previousFragmentedEncryptedFramesRemainReadable() {
        val original = ByteArray(257) { it.toByte() }
        val encrypted = ByteArrayOutputStream()
        EtaBackupEncryptedOutputStream(encrypted, "password").use { output ->
            original.forEach { output.write(it.toInt() and 0xff); output.flush() }
            output.finish()
            output.finish()
        }
        val restored = EtaBackupEncryptedInputStream(ByteArrayInputStream(encrypted.toByteArray()), "password").use { it.readBytes() }
        assertTrue(original.contentEquals(restored))
    }

    @Test
    fun guestLinksAndExecutablePermissionsSurviveExportAndStartupRestore() = runBlocking {
        val guest = File(context.filesDir, "terminal-user/proot/debian").apply { mkdirs() }
        val cycle = File(guest, "cycle").toPath()
        val script = File(guest, "run.sh").apply { writeText("#!/bin/sh\necho works\n") }
        try { java.nio.file.Files.createSymbolicLink(cycle, java.nio.file.Path.of("."))
        } catch (failure: Exception) { org.junit.Assume.assumeNoException("Host cannot create symlinks", failure) }
        try {
            val mode = EtaBackupFileTree.mode(script.toPath())?.let { 448 }
            EtaBackupFileTree.setMode(script.toPath(), mode)
            val archive = ByteArrayOutputStream()
            EtaFullBackupArchive.export(context, archive)
            // The cycle is metadata, never a recursively expanded duplicate of the guest tree.
            assertTrue(archive.size() < 1024 * 1024)
            val summary = EtaFullBackupArchive.import(context, ByteArrayInputStream(archive.toByteArray()))
            assertTrue(summary.restartRequired)
            closeDatabases()
            assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
            assertTrue(java.nio.file.Files.isSymbolicLink(cycle))
            assertEquals(".", java.nio.file.Files.readSymbolicLink(cycle).toString())
            assertEquals("#!/bin/sh\necho works\n", script.readText())
            assertEquals(mode, EtaBackupFileTree.mode(script.toPath()))
        } finally { java.nio.file.Files.deleteIfExists(cycle) }
    }

    @Test
    fun fullArchiveContainsExplicitDatabaseSettingAndArtifactSections() = runBlocking {
        // Opening each store creates the exact files whose consistency the archive is responsible for.
        EtaDatabase.get(context)
        MeetingDatabase.get(context)
        ConversationExtensionDatabase.get(context)
        LearningDatabase.get(context)
        AgentTaskDatabase.get(context)
        A2ADatabase.get(context)
        val artifact = File(context.filesDir, "meeting-artifacts/proof.txt").apply {
            parentFile?.mkdirs()
            writeText("meeting artifact")
        }
        context.getSharedPreferences("eta_a2a_inbound", Context.MODE_PRIVATE).edit()
            .putBoolean("fixture", true).commit()

        val output = ByteArrayOutputStream()
        val summary = EtaFullBackupArchive.export(context, output, password = null)
        assertTrue(summary.conversationCount >= 0)
        assertTrue(summary.privateFileCount > 0)

        var manifest: EtaFullBackupManifest? = null
        val entries = buildSet {
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    add(entry.name)
                    if (entry.name == "manifest.json") {
                        manifest = EtaBackupJsonStreams.json.decodeFromString<EtaFullBackupManifest>(
                            zip.readBytes().toString(Charsets.UTF_8),
                        )
                    }
                }
            }
        }
        assertTrue("data/databases/eta.db" in entries)
        assertTrue("data/databases/eta_meeting.db" in entries)
        assertTrue("data/databases/eta_fork.db" in entries)
        assertTrue("data/databases/eta_learning.db" in entries)
        assertTrue("data/databases/eta_task.db" in entries)
        assertTrue("data/databases/eta_a2a.db" in entries)
        assertTrue("data/databases/eta_run_metrics.db" in entries)
        assertTrue("data/shared_prefs/eta_a2a_inbound.xml" in entries)
        assertTrue("data/files/meeting-artifacts/proof.txt" in entries)
        assertTrue("data/no_backup/eta-portable-secrets.json" in entries)
        assertFalse("no_backup/eta-portable-secrets.json" in entries)
        assertTrue("manifest.json" in entries)
        val parsed = requireNotNull(manifest)
        assertEquals(
            setOf(
                "core", "runtime", "meeting", "meeting_artifacts", "conversation_extensions",
                "learning", "tasks", "a2a", "projects", "notifications", "mcp_skills", "settings",
                "secrets", "workspace",
            ),
            parsed.sections.mapTo(mutableSetOf()) { it.name },
        )
        assertTrue(parsed.sections.single { it.name == "core" }.present)
        assertTrue(parsed.sections.single { it.name == "meeting" }.present)
        assertTrue(parsed.sections.single { it.name == "secrets" }.present)
        assertTrue(artifact.isFile)
    }


    @Test
    fun encryptedArchiveCanBeInspectedWithoutChangingLiveData() = runBlocking {
        val encrypted = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, encrypted, password = "passphrase")
        val summary = EtaFullBackupArchive.inspect(
            context,
            ByteArrayInputStream(encrypted.toByteArray()),
            password = "passphrase",
        )
        assertTrue(summary.encrypted)
        assertFalse(summary.restartRequired)
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("eta-full-inspect-") })
    }

    @Test
    fun completeBackupFlushesPendingDraftsAndStagesTheirExactPrivateFile() = runBlocking {
        val store = ConversationDraftRepository.get(context)
        val chat = store.open(ConversationSubject.chat("same"))
        val meeting = store.open(ConversationSubject.meeting("same"))
        repeat(25) { store.save(chat, ConversationDraft("draft-$it", 5, 2)) }
        store.save(meeting, ConversationDraft("  会议 👋\n", 4, 1))
        // Deliberately do not await the writes: the public archive entry point must drain them.
        val archive = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, archive, "draft-password")
        val privateFile = File(context.dataDir, "shared_prefs/${ConversationDraftRepository.PREFERENCES_NAME}.xml")
        val expected = privateFile.readBytes()
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(expected))
        val items = document.getElementsByTagName("string")
        val saved = (0 until items.length).associate { index ->
            val element = items.item(index) as org.w3c.dom.Element
            element.getAttribute("name") to org.json.JSONObject(element.textContent)
        }
        assertEquals("draft-24", saved.getValue(chat.subject.key).getString("text"))
        assertEquals(2, saved.getValue(chat.subject.key).getInt("end"))
        assertEquals("  会议 👋\n", saved.getValue(meeting.subject.key).getString("text"))
        store.clearAll().await()
        val summary = EtaFullBackupArchive.import(context, ByteArrayInputStream(archive.toByteArray()), "draft-password")
        assertTrue(summary.restartRequired)
        val staged = File(context.dataDir, "eta_restore_control/staged/data/shared_prefs/${privateFile.name}")
        assertTrue(expected.contentEquals(staged.readBytes()))
        closeDatabases()
        assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
        assertTrue(expected.contentEquals(privateFile.readBytes()))
    }

    @Test fun encryptedBackupFlushesAttachmentBytesAndRestoresMeetingHistoryAndDrafts() = runBlocking(Dispatchers.IO) {
        val store = ConversationAttachmentDrafts.get(context)
        val room = MeetingRepository.createRoom(context, "Attachment backup", MeetingParticipantDraft("lead", "Lead", "host"), emptyList())
        val data = "data:image/png;base64,AQIDBA=="
        val image = io.github.mangi.eta.ui.model.PendingImageUi("image", data, data, "image/png")
        val value = store.materialize(ConversationAttachments(images = listOf(image)))
        val subject = ConversationSubject.meeting(room)
        // Leave a pending save; the public export must flush the shared attachment writer.
        store.save(store.open(subject), value)
        MeetingRepository.appendMessage(context, room, io.github.mangi.eta.fork.meeting.MeetingAuthorType.USER, null, "You", "Inspect", attachments = value)
        val original = MeetingRepository.snapshot(context, room).first()!!
        val output = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, output, "attachment-password")
        MeetingRepository.deleteRoom(context, room)
        File(android.net.Uri.parse(value.images.single().uri).path!!).delete()
        EtaFullBackupArchive.import(context, ByteArrayInputStream(output.toByteArray()), "attachment-password")
        closeDatabases()
        assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
        val fresh = ConversationAttachmentDrafts(context)
        try {
            assertEquals(value, fresh.load(fresh.open(subject)))
            org.junit.Assert.assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(android.net.Uri.parse(value.images.single().uri).path!!).readBytes())
            val restored = MeetingRepository.snapshot(context, room).first()!!
            assertEquals(original.messages, restored.messages)
            assertEquals(original.messageAttachments, restored.messageAttachments)
            assertEquals(listOf(value.images.single().dataUrl), (restored.toUiState().conversationTimeline(1).messages.single() as io.github.mangi.eta.ui.model.UserMessageUi).images)
        } finally { fresh.close() }
    }

    @Test fun encryptedFullBackupRestoresTypedMeetingToolHistoryAndItsOriginalPosition() = runBlocking(Dispatchers.IO) {
        val room = MeetingRepository.createRoom(context, "Trace backup", MeetingParticipantDraft("lead", "Lead", "host"), emptyList())
        MeetingRepository.setRoomStatus(context, room, MeetingRoomStatus.RUNNING)
        val tool = MeetingToolActivity("backup-tool-run", 1, "call", false, "run_command", MeetingToolPhase.RUNNING,
            arguments = "execute", command = "echo safe")
        MeetingRepository.recordToolActivity(context, room, "lead", "Lead", true, null, tool)
        MeetingRepository.recordToolActivity(context, room, "lead", "Lead", true, null,
            tool.copy(phase = MeetingToolPhase.SUCCESS, result = "saved result", imageCount = 2))
        MeetingRepository.setRoomStatus(context, room, MeetingRoomStatus.WAITING_FOR_USER)
        val original = MeetingRepository.snapshot(context, room).first()!!
        val archive = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, archive, "tool-history-password")
        MeetingRepository.deleteRoom(context, room)
        val result = EtaFullBackupArchive.import(context, ByteArrayInputStream(archive.toByteArray()), "tool-history-password")
        assertTrue(result.restartRequired)
        closeDatabases()
        assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
        val restored = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(original.messages, restored.messages)
        assertEquals(original.timelineEvents, restored.timelineEvents)
        assertEquals(2, MeetingDatabase.get(context).meetingDao().toolActivityEvents(room).size)
        val card = restored.toUiState().conversationTimeline(1).messages.single() as ToolActivityMessageUi
        assertEquals(ToolActivityStatusUi.Success, card.status)
        assertEquals("saved result", card.resultSummary)
        assertEquals(2, card.imageCount)
    }

    @Test fun encryptedFullBackupRestoresPartialMeetingStreamsThenRecoversWithoutReplayingThem() = runBlocking(Dispatchers.IO) {
        val room = MeetingRepository.createRoom(context, "Stream backup", MeetingParticipantDraft("lead", "Lead", "host"), emptyList())
        MeetingRepository.setRoomStatus(context, room, MeetingRoomStatus.RUNNING)
        val stream = MeetingStreamActivity("backup-stream", 1, 0, AgentEvent.AssistantBlockKind.TEXT,
            MeetingStreamPhase.STREAMING, "partial text")
        MeetingRepository.recordStreamActivity(context, room, "lead", "Lead", true, null, stream)
        val original = MeetingRepository.snapshot(context, room).first()!!
        val output = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, output, "stream-password")
        MeetingRepository.deleteRoom(context, room)
        EtaFullBackupArchive.import(context, ByteArrayInputStream(output.toByteArray()), "stream-password")
        closeDatabases()
        assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
        val restored = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(original.messages, restored.messages)
        assertEquals(original.timelineEvents, restored.timelineEvents)
        assertEquals(1, MeetingRepository.recoverInterruptedRuns(context))
        val recovered = MeetingRepository.snapshot(context, room).first()!!
        val reply = recovered.toUiState().conversationTimeline(1, streamInterruptedDetail = "Interrupted").messages.first() as io.github.mangi.eta.ui.model.AgentMessageUi
        assertFalse(reply.isStreaming)
        assertEquals("partial text\n\nInterrupted", reply.content)
        assertEquals(stream.messageId, recovered.messages.first().id)
        assertEquals(0, MeetingRepository.recoverInterruptedRuns(context))
    }

    @Test fun fullBackupRestoresMetricsWithNullUsageAndFrozenTrace() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val request = AgentRuntimeWire.RunRequest(runId = "backup-metric", prompt = "private prompt", images = emptyList(),
            config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "fixture"))
        val price = io.github.mangi.eta.agent.runtime.AgentTokenPrice.create("CNY", "2", "8", "0", now = 100)
        io.github.mangi.eta.agent.runtime.AgentTokenPriceStore.save(context, "fixture-provider", "fixture", price)
        val recorder = AgentRunTelemetry(request, priceResolver = { _, _ -> price })
        recorder.accept(AgentEvent.RoundStarted(1, 1))
        recorder.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 0)))
        val child = requireNotNull(recorder.scope.child("compaction", request.config))
        child.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 25, outputTokens = 3,
            billingBasis = io.github.mangi.eta.agent.runtime.AgentBillingTokens.OPENAI)))
        child.finish(true)
        AgentRunMetricsStore.enqueue(context, recorder.finish(true))
        val output = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, output)
        val saved = requireNotNull(AgentMetricsDatabase.get(context).dao().detail("backup-metric"))
        AgentRunMetricsStore.clear(context)
        io.github.mangi.eta.agent.runtime.AgentTokenPriceStore.remove(context, "fixture-provider", "fixture")
        assertEquals(null, AgentMetricsDatabase.get(context).dao().record("backup-metric"))
        EtaFullBackupArchive.import(context, ByteArrayInputStream(output.toByteArray()))
        closeDatabases()
        assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
        val restored = requireNotNull(AgentMetricsDatabase.get(context).dao().detail("backup-metric"))
        assertEquals(saved.run, restored.run)
        assertEquals(saved.models, restored.models)
        assertEquals(saved.trace.sortedBy { it.sequence }, restored.trace.sortedBy { it.sequence })
        assertEquals(saved.children, restored.children)
        assertEquals(25L, restored.children.single().metrics.inputTokens)
        assertEquals(0L, restored.run.metrics.inputTokens)
        assertEquals(null, restored.run.metrics.outputTokens)
        assertEquals(price, io.github.mangi.eta.agent.runtime.AgentTokenPriceStore.resolve(context, "fixture-provider", "fixture"))
        assertEquals(price.revision, restored.models.single().price?.revision)
        assertTrue(restored.models.single().priceCaptured)
    }

    @Test
    fun encryptedAndPlainExportsHandleManySmallFilesWithBufferedOutputAndProgress() = runBlocking {
        val directory = File(context.filesDir, "backup-fixtures").apply { mkdirs() }
        repeat(250) { File(directory, "file-$it.txt").writeText("文件 $it") }
        for (password in listOf(null, "password")) {
            val archive = ByteArrayOutputStream()
            var writes = 0
            var closed = false
            val borrowed = object : java.io.OutputStream() {
                override fun write(value: Int) { writes++; archive.write(value) }
                override fun write(buffer: ByteArray, offset: Int, length: Int) { writes++; archive.write(buffer, offset, length) }
                override fun close() { closed = true }
            }
            val progress = mutableListOf<EtaBackupProgress>()
            val summary = EtaFullBackupArchive.export(context, borrowed, password, onProgress = { progress += it })
            assertFalse("The exporter must not close a caller-owned stream", closed)
            assertTrue("ZIP headers must not cause thousands of tiny writes", writes < 30)
            assertTrue(summary.privateFileCount >= 250)
            assertTrue(progress.any { it.phase == "private_data" })
            assertTrue(progress.last().files >= 250)
            val inspected = EtaFullBackupArchive.inspect(context, ByteArrayInputStream(archive.toByteArray()), password)
            assertEquals(summary.privateFileCount, inspected.privateFileCount)
        }
    }

    @Test
    fun preservesEmptyDirectoriesAndAcceptsPreviousManifestVersions() = runBlocking {
        File(context.filesDir, "empty/nested").mkdirs()
        val archive = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, archive)
        val bytes = archive.toByteArray()
        val current = EtaFullBackupArchive.inspect(context, ByteArrayInputStream(bytes))
        for (version in listOf(1, 2)) {
            val legacy = changeManifest(bytes) { it.copy(schemaVersion = version, directories = emptyList(), links = emptyList()) }
            assertEquals(current, EtaFullBackupArchive.inspect(context, ByteArrayInputStream(legacy)))
        }
        val entries = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) entries += (zip.nextEntry ?: break).name
        }
        assertTrue("data/files/empty/nested/" in entries)
    }

    @Test
    fun linkDescriptorsAreValidatedWithoutFollowingOrMaterializingThem() = runBlocking {
        File(context.filesDir, "terminal-user/proot/debian/empty").mkdirs()
        val archive = ByteArrayOutputStream()
        val before = EtaFullBackupArchive.export(context, archive)
        val withLink = changeManifest(archive.toByteArray()) {
            it.copy(links = listOf(EtaBackupLinkManifest("data/files/terminal-user/proot/debian/empty/cycle", "..")))
        }
        val summary = EtaFullBackupArchive.inspect(context, ByteArrayInputStream(withLink))
        assertEquals(before.privateFileCount + 1, summary.privateFileCount)
        assertFalse(File(context.filesDir, "terminal-user/proot/debian/empty/cycle").exists())
    }

    @Test
    fun maliciousLinkPathsAncestorsTargetsAndModesAreRejected() = runBlocking {
        val archive = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, archive)
        val original = archive.toByteArray()
        val fixtures: List<(EtaFullBackupManifest) -> EtaFullBackupManifest> = listOf(
            { it.copy(links = listOf(EtaBackupLinkManifest("data/files/escape", "../../outside"))) },
            { it.copy(links = listOf(EtaBackupLinkManifest("data/no_backup/secret", "/outside"))) },
            { it.copy(links = listOf(EtaBackupLinkManifest("/data/files/absolute", "target"))) },
            { it.copy(links = listOf(EtaBackupLinkManifest("data/files/parent", "target")),
                directories = it.directories + EtaBackupDirectoryManifest("data/files/parent/child")) },
            { it.copy(entries = it.entries.mapIndexed { index, entry -> if (index == 0) entry.copy(mode = 4095) else entry }) },
        )
        for (fixture in fixtures) {
            val result = runCatching {
                EtaFullBackupArchive.inspect(context, ByteArrayInputStream(changeManifest(original, fixture)))
            }
            assertTrue("Unsafe metadata must be rejected: ${result.exceptionOrNull()}", result.exceptionOrNull() is EtaBackupException)
        }
    }

    private fun changeManifest(bytes: ByteArray, change: (EtaFullBackupManifest) -> EtaFullBackupManifest): ByteArray {
        val result = ByteArrayOutputStream()
        ZipOutputStream(result).use { output ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    output.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "manifest.json") {
                        val manifest = EtaBackupJsonStreams.json.decodeFromString<EtaFullBackupManifest>(input.readBytes().toString(Charsets.UTF_8))
                        output.write(EtaBackupJsonStreams.json.encodeToString(change(manifest)).toByteArray(Charsets.UTF_8))
                    } else input.copyTo(output)
                    output.closeEntry()
                }
            }
        }
        return result.toByteArray()
    }

    @Test
    fun importEntryPointStillAcceptsLegacyJsonBackups() = runBlocking {
        val legacy = EtaBackupDocument(exportedAt = 0)
        val summary = EtaFullBackupArchive.import(
            context,
            ByteArrayInputStream(
                EtaBackupJsonStreams.json.encodeToString(legacy).toByteArray(Charsets.UTF_8),
            ),
        )
        assertFalse(summary.restartRequired)
        assertEquals(0, summary.conversationCount)
    }

    @Test
    fun nestedFoldersSurvivePlainFullBackupAndStartupRestore() = nestedFolderBackupRoundTrip(null)

    @Test
    fun nestedFoldersSurviveEncryptedFullBackupAndStartupRestore() = nestedFolderBackupRoundTrip("folder-password")

    private fun nestedFolderBackupRoundTrip(password: String?) = runBlocking {
        val repository = ConversationExtensionRepository
        val root = repository.createFolder(context, "Root")
        val child = repository.createFolder(context, "Child", parentId = root)
        val leaf = repository.createFolder(context, "Leaf", parentId = child)
        repository.ensureConversations(context, listOf("nested-chat"))
        repository.moveConversation(context, "nested-chat", leaf)
        repository.archiveConversation(context, "nested-chat", now = 123L)
        val archive = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, archive, password)
        assertEquals(child, ConversationExtensionDatabase.get(context).conversationExtensionDao().folder(leaf)!!.parentId)
        EtaFullBackupArchive.inspect(context, ByteArrayInputStream(archive.toByteArray()), password)
        val dao = ConversationExtensionDatabase.get(context).conversationExtensionDao()
        repository.moveFolder(context, leaf, null)
        val summary = EtaFullBackupArchive.import(context, ByteArrayInputStream(archive.toByteArray()), password)
        assertTrue(summary.restartRequired)
        assertEquals(null, dao.folder(leaf)!!.parentId) // Import only stages the old hierarchy.
        val staged = File(context.dataDir, "eta_restore_control/staged/data/databases/eta_fork.db")
        // SQLite can update/remove WAL sidecars even for a read-only connection. Inspect a copy
        // so the integrity-checked staging area remains untouched before startup applies it.
        val inspection = File(context.cacheDir, "folder-backup-inspection").apply { mkdirs() }
        try {
            staged.parentFile!!.listFiles()!!.filter { it.name == staged.name || it.name.startsWith("${staged.name}-") }
                .forEach { it.copyTo(File(inspection, it.name), overwrite = true) }
            SQLiteDatabase.openDatabase(File(inspection, staged.name).absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT parent_id FROM conversation_folders WHERE id = ?", arrayOf(leaf)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(child, cursor.getString(0))
                }
            }
        } finally { inspection.deleteRecursively() }
        closeDatabases()
        assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
        val restored = ConversationExtensionDatabase.get(context).conversationExtensionDao()
        assertEquals(root, restored.folder(child)!!.parentId)
        assertEquals(child, restored.folder(leaf)!!.parentId)
        assertEquals(leaf, restored.extension("nested-chat")!!.folderId)
        assertEquals(123L, restored.extension("nested-chat")!!.archivedAt)
    }

    @Test
    fun fullArchiveImportStagesCompleteDataWithoutChangingLiveFiles() = runBlocking {
        val artifact = File(context.filesDir, "chat-artifacts/import-proof.txt").apply {
            parentFile?.mkdirs()
            writeText("backup-version")
        }
        val archive = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, archive, password = "portable-password")
        artifact.writeText("live-version-after-export")
        val control = File(context.dataDir, "eta_restore_control").apply { mkdirs() }
        File(control, "result").writeText("stale result from an earlier restore")

        val summary = EtaFullBackupArchive.import(
            context,
            ByteArrayInputStream(archive.toByteArray()),
            password = "portable-password",
        )

        assertTrue(summary.restartRequired)
        assertEquals("live-version-after-export", artifact.readText())
        assertEquals("pending", File(control, "state").readText())
        assertFalse(File(control, "result").exists())
        assertEquals(
            "backup-version",
            File(control, "staged/data/files/chat-artifacts/import-proof.txt").readText(),
        )
        assertTrue(File(control, "staged/manifest.json").isFile)
        assertTrue(File(control, "pre-restore.eta-backup").isFile)
    }

    @Test
    fun portableCredentialMigrationFileIsParsedAndRemoved() = runBlocking {
        val migration = File(context.noBackupFilesDir, "eta-portable-secrets.json")
        migration.writeText(
            EtaBackupJsonStreams.json.encodeToString(EtaPortableSecrets()),
        )

        EtaFullBackupArchive.restorePortableSecretsAfterStartup(context)

        assertFalse(migration.exists())
    }

    @Test
    fun fullRestorePreservesSetupReceiptsAndEditedSettingsBeforePendingRecovery() = runBlocking(Dispatchers.IO) {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(listOf("root", "pending", "complete").map { ConversationEntity(it, it, false, createdAt = 1, updatedAt = 1) })
        dao.insertBranch(ConversationBranchEntity("root", "root", createdAt = 1))
        val folder = ConversationExtensionRepository.createFolder(context, "Branch folder", 1)
        val project = ProjectRepository.create(context, ProjectDraft("Branch project", "app", "branch-root", "Root", "APP_WORKSPACE"), 1)
        val pending = ConversationBranchSetupEntity("pending", "pending-generation", folder, 8192, project, 1)
        val complete = pending.copy(conversationId = "complete", setupId = "complete-generation")
        for (setup in listOf(pending, complete)) {
            dao.insertBranch(ConversationBranchEntity(setup.conversationId, "root", "root", "root", "point", 0, createdAt = 1))
            dao.insertBranchSetup(setup)
        }
        // 模拟两个目标库已提交，但核心完成确认尚未提交的断点。
        ConversationExtensionRepository.initializeBranch(context, pending, false)
        ProjectRepository.initializeBranch(context, pending, false)
        assertTrue(ConversationBranchSetupCoordinator.ensure(context, "complete"))
        for (id in listOf("pending", "complete")) {
            ConversationExtensionRepository.setContextWindowOverride(context, id, 32768)
            ConversationExtensionRepository.moveConversation(context, id, null)
            ProjectRepository.unbindSubject(context, ProjectSubjectType.CONVERSATION, id)
        }
        val output = ByteArrayOutputStream()
        EtaFullBackupArchive.export(context, output)
        ConversationExtensionRepository.setContextWindowOverride(context, "pending", 4096)
        EtaFullBackupArchive.import(context, ByteArrayInputStream(output.toByteArray()))
        closeDatabases()
        assertTrue(EtaFullBackupArchive.applyPendingRestore(context))
        val restored = EtaDatabase.get(context).conversationDao()
        assertEquals(pending, restored.branchSetup("pending"))
        for (setup in listOf(pending, complete)) {
            assertTrue(ConversationExtensionDatabase.get(context).conversationExtensionDao().hasBranchSetupReceipt(setup.setupId))
            assertTrue(ProjectDatabase.get(context).projectDao().hasBranchSetupReceipt(setup.setupId))
        }
        ConversationBranchSetupCoordinator.recoverPending(context)
        assertEquals("inherited", restored.branchSetup("pending")!!.completionMode)
        for (id in listOf("pending", "complete")) {
            assertEquals(32768, ConversationExtensionRepository.contextWindowOverride(context, id))
            assertEquals(null, ConversationExtensionDatabase.get(context).conversationExtensionDao().extension(id)!!.folderId)
            assertEquals(null, ProjectRepository.bindingForSubject(context, ProjectSubjectType.CONVERSATION, id))
        }
    }

    private fun closeDatabases() {
        runCatching { ProjectDatabase.closeForTests() }
        runCatching { AgentMetricsDatabase.closeForTests() }
        runCatching { EtaDatabase.closeForTests() }
        runCatching { A2ADatabase.closeForTests() }
        runCatching { ConversationExtensionDatabase.closeForTests() }
        runCatching { AgentTaskDatabase.closeForTests() }
        runCatching { MeetingDatabase.closeForTests() }
        runCatching { LearningDatabase.closeForTests() }
    }

    private fun resetPortableSecretFixtures() {
        File(context.noBackupFilesDir, "eta-portable-secrets.json").delete()
        File(context.noBackupFilesDir, "provider_credentials").deleteRecursively()
        listOf("eta_mcp_secrets", "eta_a2a_secrets", "eta_speech_secrets").forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}

private object InputStreamCopy {
    fun copy(input: java.io.InputStream): ByteArray = input.readBytes()
}
