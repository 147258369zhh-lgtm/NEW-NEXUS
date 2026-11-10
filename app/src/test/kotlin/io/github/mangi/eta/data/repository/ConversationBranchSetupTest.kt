package io.github.mangi.eta.data.repository

import android.app.Application
import androidx.room.withTransaction
import io.github.mangi.eta.data.db.*
import io.github.mangi.eta.fork.conversations.*
import io.github.mangi.eta.fork.projects.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
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
class ConversationBranchSetupTest {
    private val context = RuntimeEnvironment.getApplication()
    private val core get() = EtaDatabase.get(context).conversationDao()
    private val extensions get() = ConversationExtensionDatabase.get(context).conversationExtensionDao()
    private val projects get() = ProjectDatabase.get(context).projectDao()

    private fun close() {
        EtaDatabase.closeForTests()
        ConversationExtensionDatabase.closeForTests()
        ProjectDatabase.closeForTests()
    }
    @Before fun reset() {
        close()
        listOf("eta.db", "eta_fork.db", "eta_projects.db").forEach(context::deleteDatabase)
    }
    @After fun tearDown() { close() }

    private suspend fun source(): Pair<String, String> {
        core.insertConversations(listOf(ConversationEntity("root", "Root", false, isPinned = true, createdAt = 10, updatedAt = 10)))
        val folder = ConversationExtensionRepository.createFolder(context, "Folder", 10)
        extensions.upsertExtension(ConversationExtensionEntity("root", folderId = folder, contextWindowOverride = 8192,
            archivedAt = 11, archiveReason = "manual", autoArchiveExempt = true, lastActiveAt = 10, createdAt = 10, updatedAt = 11))
        val project = ProjectRepository.create(context, ProjectDraft("Project", "app", "root-key", "Root", "APP_WORKSPACE"), 10)
        ProjectRepository.bindSubject(context, project, ProjectSubjectType.CONVERSATION, "root", 10)
        return folder to project
    }
    private suspend fun child(id: String = "child"): ConversationBranchSetupEntity {
        val setup = ConversationBranchSetupCoordinator.capture(context, "root", id, 20)
        EtaDatabase.get(context).withTransaction {
            core.insertConversations(listOf(ConversationEntity(id, id, false, createdAt = 20, updatedAt = 20)))
            core.insertBranch(ConversationBranchEntity("root", "root", createdAt = 10))
            core.insertBranch(ConversationBranchEntity(id, "root", "root", "root", "point", 0, createdAt = 20))
            core.insertBranchSetup(setup)
        }
        return setup
    }
    private suspend fun binding(id: String = "child") = ProjectRepository.bindingForSubject(context, ProjectSubjectType.CONVERSATION, id)?.projectId

    @Test(timeout = 30_000) fun frozenSettingsSurviveSourceChangesAndDoNotInheritArchiveOrPin() = runBlocking(Dispatchers.IO) {
        val (folder, project) = source()
        val setup = child()
        ConversationExtensionRepository.setContextWindowOverride(context, "root", 16384)
        ConversationExtensionRepository.moveConversation(context, "root", null)
        ProjectRepository.unbindSubject(context, ProjectSubjectType.CONVERSATION, "root")
        assertTrue(ConversationBranchSetupCoordinator.ensure(context, "child"))
        val result = extensions.extension("child")!!
        assertEquals(folder, result.folderId)
        assertEquals(8192, result.contextWindowOverride)
        assertNull(result.archivedAt)
        assertFalse(result.autoArchiveExempt)
        assertFalse(core.conversationIdentities().single { it.id == "child" }.isPinned)
        assertEquals(project, binding())
        assertTrue(extensions.hasBranchSetupReceipt(setup.setupId))
        assertTrue(projects.hasBranchSetupReceipt(setup.setupId))
        assertEquals("inherited", core.branchSetup("child")!!.completionMode)
    }

    @Test(timeout = 30_000) fun confirmationFailureThenRestartDoesNotOverwriteUserChanges() = runBlocking(Dispatchers.IO) {
        source()
        val setup = child()
        val sql = EtaDatabase.get(context).openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER reject_setup BEFORE UPDATE OF completed_at ON conversation_branch_setups BEGIN SELECT RAISE(ABORT, 'test rejection'); END")
        assertFalse(ConversationBranchSetupCoordinator.ensure(context, "child"))
        assertNull(core.branchSetup("child")!!.completedAt)
        assertEquals("SETUP_STORAGE_UNAVAILABLE", core.branchSetup("child")!!.errorCode)
        assertTrue(extensions.hasBranchSetupReceipt(setup.setupId))
        assertTrue(projects.hasBranchSetupReceipt(setup.setupId))
        ConversationExtensionRepository.moveConversation(context, "child", null)
        ConversationExtensionRepository.setContextWindowOverride(context, "child", 32768)
        ProjectRepository.unbindSubject(context, ProjectSubjectType.CONVERSATION, "child")
        sql.execSQL("DROP TRIGGER reject_setup")
        close()
        ConversationBranchSetupCoordinator.recoverPending(context)
        assertEquals(32768, extensions.extension("child")!!.contextWindowOverride)
        assertNull(extensions.extension("child")!!.folderId)
        assertNull(binding())
        assertNotNull(core.branchSetup("child")!!.completedAt)
    }

    @Test(timeout = 30_000) fun folderFailureLeavesNoReceiptAndCanBeAbandonedWithoutResettingSettings() = runBlocking(Dispatchers.IO) {
        val (folder, _) = source()
        val setup = child()
        ConversationExtensionRepository.deleteFolder(context, folder)
        assertFalse(ConversationBranchSetupCoordinator.ensure(context, "child"))
        assertEquals("FOLDER_UNAVAILABLE", core.branchSetup("child")!!.errorCode)
        assertFalse(extensions.hasBranchSetupReceipt(setup.setupId))
        assertFalse(projects.hasBranchSetupReceipt(setup.setupId))
        ConversationExtensionRepository.ensureConversations(context, listOf("child"))
        ConversationExtensionRepository.setContextWindowOverride(context, "child", 4096)
        assertTrue(ConversationBranchSetupCoordinator.ensure(context, "child", useCurrent = true))
        assertEquals(4096, extensions.extension("child")!!.contextWindowOverride)
        assertNull(binding())
        assertEquals("current_settings", core.branchSetup("child")!!.completionMode)
    }

    @Test(timeout = 30_000) fun projectFailureKeepsAppliedFolderAndRetryDoesNotResetEditedContext() = runBlocking(Dispatchers.IO) {
        val (folder, project) = source()
        val setup = child()
        ProjectRepository.setArchived(context, project, true)
        assertFalse(ConversationBranchSetupCoordinator.ensure(context, "child"))
        assertEquals("PROJECT_UNAVAILABLE", core.branchSetup("child")!!.errorCode)
        assertEquals(folder, extensions.extension("child")!!.folderId)
        assertTrue(extensions.hasBranchSetupReceipt(setup.setupId))
        assertFalse(projects.hasBranchSetupReceipt(setup.setupId))
        ConversationExtensionRepository.setContextWindowOverride(context, "child", 16384)
        ProjectRepository.setArchived(context, project, false)
        assertTrue(ConversationBranchSetupCoordinator.ensure(context, "child"))
        assertEquals(16384, extensions.extension("child")!!.contextWindowOverride)
        assertEquals(project, binding())
    }

    @Test(timeout = 30_000) fun useCurrentKeepsBothAlreadyAppliedAndManuallyChosenParts() = runBlocking(Dispatchers.IO) {
        val (_, project) = source()
        child()
        ProjectRepository.delete(context, project)
        assertFalse(ConversationBranchSetupCoordinator.ensure(context, "child"))
        val replacement = ProjectRepository.create(context, ProjectDraft("Replacement", "app", "other-key", "Other", "APP_WORKSPACE"), 30)
        ProjectRepository.bindSubject(context, replacement, ProjectSubjectType.CONVERSATION, "child", 30)
        ConversationExtensionRepository.moveConversation(context, "child", null)
        assertTrue(ConversationBranchSetupCoordinator.ensure(context, "child", useCurrent = true))
        assertNull(extensions.extension("child")!!.folderId)
        assertEquals(replacement, binding())
        assertTrue(ConversationBranchSetupCoordinator.ensure(context, "child"))
        assertEquals(replacement, binding())
    }

    @Test(timeout = 30_000) fun concurrentRequestsAreIdempotentAndDeleteNeverRecreatesLinks() = runBlocking(Dispatchers.IO) {
        source()
        val setup = child()
        val results = coroutineScope { List(8) { async { ConversationBranchSetupCoordinator.ensure(context, "child") } }.awaitAll() }
        assertTrue(results.all { it })
        val order = extensions.extension("child")!!.folderSortOrder
        assertTrue(ConversationBranchSetupCoordinator.ensure(context, "child"))
        assertEquals(order, extensions.extension("child")!!.folderSortOrder)
        core.deleteConversation("child")
        assertNull(core.branchSetup("child"))
        coroutineScope {
            awaitAll(async { ConversationBranchSetupCoordinator.cleanDeleted(context, "child") },
                async { ConversationBranchSetupCoordinator.ensure(context, "child") })
        }
        assertNull(extensions.extension("child"))
        assertNull(binding())
        assertFalse(extensions.hasBranchSetupReceipt(setup.setupId))
        assertFalse(projects.hasBranchSetupReceipt(setup.setupId))
    }

    @Test(timeout = 30_000) fun cleaningExistingConversationDoesNotRemoveItsSettings() = runBlocking(Dispatchers.IO) {
        source(); val setup = child()
        assertTrue(ConversationBranchSetupCoordinator.ensure(context, "child"))
        ConversationBranchSetupCoordinator.cleanDeleted(context, "child")
        assertNotNull(extensions.extension("child"))
        assertNotNull(binding())
        assertTrue(extensions.hasBranchSetupReceipt(setup.setupId))
    }

    @Test(timeout = 30_000) fun oldBranchWithoutSnapshotRemainsIndependent() = runBlocking(Dispatchers.IO) {
        source()
        core.insertConversations(listOf(ConversationEntity("old", "Old", false, createdAt = 20, updatedAt = 20)))
        core.insertBranch(ConversationBranchEntity("old", "root", sourceConversationId = "root", sourceMessageId = "point", sourceSortIndex = 0, createdAt = 20))
        assertTrue(ConversationBranchSetupCoordinator.ensure(context, "old"))
        assertNull(extensions.extension("old"))
        assertNull(binding("old"))
    }

    @Test(timeout = 30_000) fun threePreviousSchemasUpgradeWithoutChangingExistingSettings() = runBlocking(Dispatchers.IO) {
        val (folder, project) = source()
        val db = EtaDatabase.get(context).openHelper.writableDatabase
        db.execSQL("DROP TABLE conversation_branch_setups"); db.version = 25
        val ext = ConversationExtensionDatabase.get(context).openHelper.writableDatabase
        ext.execSQL("DROP TABLE branch_setup_receipts"); ext.version = 4
        val proj = ProjectDatabase.get(context).openHelper.writableDatabase
        proj.execSQL("DROP TABLE branch_setup_receipts"); proj.version = 1
        close()
        assertEquals(27, EtaDatabase.get(context).openHelper.writableDatabase.version)
        assertEquals(5, ConversationExtensionDatabase.get(context).openHelper.writableDatabase.version)
        assertEquals(2, ProjectDatabase.get(context).openHelper.writableDatabase.version)
        assertTrue(core.branchSetupsPage(8, 0).isEmpty())
        assertEquals(folder, extensions.extension("root")!!.folderId)
        assertEquals(8192, extensions.extension("root")!!.contextWindowOverride)
        assertEquals(project, binding("root"))
        val setup = child()
        assertTrue(ConversationBranchSetupCoordinator.ensure(context, "child"))
        assertTrue(projects.hasBranchSetupReceipt(setup.setupId))
    }
}
