package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.*
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.mangi.eta.ui.app.*
import io.github.mangi.eta.ui.model.*
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.github.mangi.eta.fork.conversations.ConversationExtensionDatabase
import io.github.mangi.eta.fork.conversations.ConversationExtensionRepository
import io.github.mangi.eta.fork.projects.ProjectDatabase

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaBranchBackupTest {
    private val context = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        EtaDatabase.closeForTests(); context.deleteDatabase("eta.db")
        ConversationExtensionDatabase.closeForTests(); context.deleteDatabase("eta_fork.db")
        ProjectDatabase.closeForTests(); context.deleteDatabase("eta_projects.db")
        SettingsDataStore.init(context); ProviderRepository.init(context); AgentMemoryRepository.init(context)
        runBlocking { SettingsDataStore.setSelection(null, null); SettingsDataStore.setOfficialModelCatalogRevision(0) }
        AgentMemoryRepository.replaceAll("")
    }
    private fun bytes(doc: EtaBackupDocument) = ByteArrayInputStream(Json.encodeToString(doc).toByteArray())
    private fun conversation(id: String) = ConversationEntity(id, id, false, createdAt = 1, updatedAt = 1)

    @Test fun committedLogicalImportClearsDraftsAndRejectsLateEditorsForReusedIds() = runBlocking(Dispatchers.IO) {
        val store = ConversationDraftRepository.get(context)
        val subject = ConversationSubject.chat("reused")
        val chat = store.open(subject)
        val meeting = store.open(ConversationSubject.meeting("meeting"))
        store.save(chat, ConversationDraft("old local chat")).await()
        store.save(meeting, ConversationDraft("old local meeting")).await()
        EtaBackupRepository.import(context, bytes(EtaBackupDocument(exportedAt = 0, conversations = listOf(conversation("reused")))))
        store.save(chat, ConversationDraft("late onDispose")).await()
        store.save(meeting, ConversationDraft("late meeting onDispose")).await()
        assertNull(store.load(store.open(subject)))
        assertNull(store.load(store.open(meeting.subject)))
        assertNotNull(EtaDatabase.get(context).conversationDao().conversation("reused"))
    }

    @Test fun rejectedLogicalImportLeavesExistingDraftAndLeaseUsable() = runBlocking(Dispatchers.IO) {
        val store = ConversationDraftRepository.get(context)
        val lease = store.open(ConversationSubject.chat("keep"))
        store.save(lease, ConversationDraft("keep draft")).await()
        val duplicate = conversation("invalid")
        assertTrue(runCatching {
            EtaBackupRepository.import(context, bytes(EtaBackupDocument(exportedAt = 0, conversations = listOf(duplicate, duplicate))))
        }.isFailure)
        assertEquals("keep draft", store.load(lease)!!.text)
        store.save(lease, ConversationDraft("still editable")).await()
        assertEquals("still editable", store.load(lease)!!.text)
    }

    @Test fun logicalJsonRoundTripsBranchesOriginsAndMissingOriginalParent() = runBlocking(Dispatchers.IO) {
        val source = AgentChatHomeUiState(listOf(UserMessageUi("u", "问题"), AgentMessageUi("a", "答案")),
            journal = listOf(ConversationMessage("user", "问题", messageId = "u"), ConversationMessage("assistant", "答案", messageId = "a")),
            input = "", isStreaming = false, thinkingEnabled = false)
        val plan = AgentConversationBranchPlanner.create("root", source, mapOf("u" to 0, "a" to 1), "a", null, "child")
        AgentConversationPersistence().save(context, AgentConversationStore.Snapshot("child", mapOf("root" to source, "child" to plan.state),
            mapOf("root" to "源会话", "child" to "分支"), mapOf("root" to 1, "child" to 2), branchDrafts = listOf(plan.draft)))
        val dao = EtaDatabase.get(context).conversationDao()
        dao.deleteConversation("root")
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        val document = Json.decodeFromString<EtaBackupDocument>(output.toString("UTF-8"))
        assertEquals(8, document.schemaVersion)
        assertEquals(1, document.branches.size)
        assertEquals(2, document.messageOrigins.size)
        assertNull(document.branches.single().parentConversationId)
        dao.deleteConversations()
        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        assertEquals("root", dao.branch("child")?.sourceConversationId)
        assertEquals("root", dao.branch("child")?.familyId)
        assertEquals(2, dao.messageOrigins("child").size)
        assertEquals("答案", dao.messagesPage("child", 8, 0).last().content)
    }

    @Test fun cyclesAndIncorrectOriginOwnershipAreRejectedBeforeReplacingData() = runBlocking(Dispatchers.IO) {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(conversation("keep")))
        val nodes = listOf(ConversationBranchEntity("a", "family", "b", "b", "point", 0, createdAt = 1),
            ConversationBranchEntity("b", "family", "a", "a", "point", 0, createdAt = 1))
        val cyclic = EtaBackupDocument(exportedAt = 0, conversations = listOf(conversation("a"), conversation("b")), branches = nodes)
        try { EtaBackupRepository.import(context, bytes(cyclic)); fail("Cycle accepted") } catch (_: EtaBackupException) { }
        assertEquals(listOf("keep"), dao.conversationIdentities().map { it.id })
        val invalid = cyclic.copy(branches = listOf(ConversationBranchEntity("a", "a", createdAt = 1)),
            messages = listOf(ConversationMessageEntity("message", "b", 0, "user", "text")),
            messageOrigins = listOf(ConversationMessageOriginEntity("message", "a", "source", "point", 0)))
        try { EtaBackupRepository.import(context, bytes(invalid)); fail("Wrong owner accepted") } catch (_: EtaBackupException) { }
        assertEquals(listOf("keep"), dao.conversationIdentities().map { it.id })
    }

    @Test fun schema4WithoutBranchesStillImportsWithEmptyNewTables() = runBlocking(Dispatchers.IO) {
        EtaBackupRepository.import(context, bytes(EtaBackupDocument(exportedAt = 0, schemaVersion = 4, conversations = listOf(conversation("old")))))
        val dao = EtaDatabase.get(context).conversationDao()
        assertEquals(listOf("old"), dao.conversationIdentities().map { it.id })
        assertTrue(dao.branchesPage(8, 0).isEmpty())
        assertTrue(dao.messageOriginsPage(8, 0).isEmpty())
    }

    private fun setupDocument(setup: ConversationBranchSetupEntity) = EtaBackupDocument(exportedAt = 0,
        conversations = listOf(conversation("child")),
        branches = listOf(ConversationBranchEntity("child", "root", sourceConversationId = "root",
            sourceMessageId = "point", sourceSortIndex = 0, createdAt = 1)), branchSetups = listOf(setup))

    @Test fun schema6RoundTripUsesNewReceiptsAndWaitsForMissingDependencies() = runBlocking(Dispatchers.IO) {
        val setup = ConversationBranchSetupEntity("child", "old-generation", folderId = "missing-folder", contextWindow = 8192,
            createdAt = 1, completedAt = 2, completionMode = "inherited")
        EtaBackupRepository.import(context, bytes(setupDocument(setup)))
        val dao = EtaDatabase.get(context).conversationDao()
        val restored = dao.branchSetup("child")!!
        assertNotEquals(setup.setupId, restored.setupId)
        assertNull(restored.completedAt)
        assertFalse(ConversationBranchSetupCoordinator.ensure(context, "child"))
        assertEquals("FOLDER_UNAVAILABLE", dao.branchSetup("child")!!.errorCode)
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        val exported = Json.decodeFromString<EtaBackupDocument>(output.toString("UTF-8"))
        assertEquals(listOf(dao.branchSetup("child")!!), exported.branchSetups)
    }

    @Test fun logicalRestoreDoesNotReactivateExplicitlyAbandonedInheritance() = runBlocking(Dispatchers.IO) {
        ConversationExtensionRepository.ensureConversations(context, listOf("child"))
        ConversationExtensionRepository.setContextWindowOverride(context, "child", 4096)
        val setup = ConversationBranchSetupEntity("child", "old-generation", folderId = "missing-folder", contextWindow = 8192,
            createdAt = 1, completedAt = 2, completionMode = "current_settings")
        EtaBackupRepository.import(context, bytes(setupDocument(setup)))
        assertTrue(ConversationBranchSetupCoordinator.ensure(context, "child"))
        assertEquals(4096, ConversationExtensionRepository.contextWindowOverride(context, "child"))
        assertEquals("current_settings", EtaDatabase.get(context).conversationDao().branchSetup("child")!!.completionMode)
    }

    @Test fun schema5BranchesDoNotAcquireGuessedSettings() = runBlocking(Dispatchers.IO) {
        EtaBackupRepository.import(context, bytes(setupDocument(ConversationBranchSetupEntity("child", "id", createdAt = 1))
            .copy(schemaVersion = 5, branchSetups = emptyList())))
        assertNull(EtaDatabase.get(context).conversationDao().branchSetup("child"))
        assertTrue(ConversationBranchSetupCoordinator.ensure(context, "child"))
        assertNull(ConversationExtensionRepository.contextWindowOverride(context, "child"))
    }

    @Test fun invalidSetupRecordsAreRejectedBeforeReplacingLiveData() = runBlocking(Dispatchers.IO) {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(conversation("keep")))
        val valid = ConversationBranchSetupEntity("child", "setup-id", createdAt = 1)
        val invalid = listOf(valid.copy(contextWindow = 2), valid.copy(setupId = ""), valid.copy(completedAt = 2),
            valid.copy(errorCode = "unknown"), valid.copy(conversationId = "keep"))
        for (row in invalid) {
            try { EtaBackupRepository.import(context, bytes(setupDocument(row))); fail("Invalid snapshot accepted") }
            catch (_: EtaBackupException) { }
            assertEquals(listOf("keep"), dao.conversationIdentities().map { it.id })
        }
        try { EtaBackupRepository.import(context, bytes(setupDocument(valid).copy(branchSetups = listOf(valid, valid)))); fail("Duplicate accepted") }
        catch (_: EtaBackupException) { }
        assertEquals(listOf("keep"), dao.conversationIdentities().map { it.id })
    }
}
