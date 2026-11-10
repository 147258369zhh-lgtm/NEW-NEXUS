package io.github.mangi.eta.fork.conversations

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
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
class ConversationFolderRepositoryTest {
    private lateinit var context: Context
    private val repository = ConversationExtensionRepository
    private val dao get() = ConversationExtensionDatabase.get(context).conversationExtensionDao()

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ConversationExtensionDatabase.closeForTests()
        context.deleteDatabase(ConversationExtensionDatabase.DATABASE_NAME)
    }

    @After fun tearDown() {
        ConversationExtensionDatabase.closeForTests()
        context.deleteDatabase(ConversationExtensionDatabase.DATABASE_NAME)
    }

    private suspend fun folder(name: String, parent: String? = null) =
        repository.createFolder(context, name, now = 100L, parentId = parent)

    private suspend fun fails(code: String, operation: suspend () -> Unit) {
        try { operation(); fail("Expected $code") }
        catch (failure: ConversationFolderException) { assertEquals(code, failure.code) }
    }

    @Test fun namesAreUniquePerParentAndConflictsNeverReplaceExistingFolders() = runBlocking {
        val a = folder("A")
        val b = folder("B")
        val child = folder("Shared", a)
        folder("Shared", b)
        val root = folder("Shared")
        repository.ensureConversations(context, listOf("chat"))
        repository.moveConversation(context, "chat", root)
        fails("DUPLICATE_NAME") { folder("Shared") }
        fails("DUPLICATE_NAME") { folder(" Shared ", a) }
        fails("DUPLICATE_NAME") { repository.renameFolder(context, dao.folder(b)!!.toModel(), "A") }
        fails("DUPLICATE_NAME") { repository.moveFolder(context, child, b) }
        assertEquals(5, dao.folders().size)
        assertEquals(root, dao.extension("chat")!!.folderId)
        assertEquals(a, dao.folder(child)!!.parentId)
        assertEquals("B", dao.folder(b)!!.name)
    }

    @Test fun invalidNamesAndMissingParentsDoNotWrite() = runBlocking {
        for (name in listOf(" ", "a/b", "a\\b", "line\nbreak", "x".repeat(81))) {
            fails("INVALID_NAME") { folder(name) }
        }
        fails("NOT_FOUND") { folder("Child", "missing") }
        assertTrue(dao.folders().isEmpty())
        val id = folder(" Trimmed ")
        assertEquals("Trimmed", dao.folder(id)!!.name)
    }

    @Test fun renameWithStaleSnapshotPreservesCurrentParentAndOrder() = runBlocking {
        val a = folder("A")
        val b = folder("B")
        val child = folder("Child", a)
        val stale = dao.folder(child)!!.toModel()
        repository.moveFolder(context, child, b)
        val moved = dao.folder(child)!!
        repository.renameFolder(context, stale, "Renamed", now = 300L)
        assertEquals(moved.copy(name = "Renamed", updatedAt = 300L), dao.folder(child))
    }

    @Test fun movingSubtreeKeepsChildrenAndConversationMembership() = runBlocking {
        val a = folder("A")
        val b = folder("B")
        val child = folder("Child", a)
        val leaf = folder("Leaf", child)
        repository.ensureConversations(context, listOf("parent-chat", "leaf-chat"))
        repository.moveConversation(context, "parent-chat", child)
        repository.moveConversation(context, "leaf-chat", leaf)
        repository.moveFolder(context, child, b)
        assertEquals(b, dao.folder(child)!!.parentId)
        assertEquals(child, dao.folder(leaf)!!.parentId)
        assertEquals(child, dao.extension("parent-chat")!!.folderId)
        assertEquals(leaf, dao.extension("leaf-chat")!!.folderId)
        repository.moveFolder(context, child, null)
        assertNull(dao.folder(child)!!.parentId)
    }

    @Test fun cyclesAndDepthAreRejectedForTheEntireSubtree() = runBlocking {
        val chain = mutableListOf<String>()
        repeat(8) { chain += folder("Level $it", chain.lastOrNull()) }
        val subtree = folder("Subtree")
        val leaf = folder("Leaf", subtree)
        val before = dao.folders()
        fails("DEPTH_LIMIT") { folder("Too deep", chain.last()) }
        fails("DEPTH_LIMIT") { repository.moveFolder(context, subtree, chain[6]) }
        fails("CYCLE") { repository.moveFolder(context, subtree, leaf) }
        fails("CYCLE") { repository.moveFolder(context, subtree, subtree) }
        fails("NOT_FOUND") { repository.moveFolder(context, subtree, "missing") }
        fails("NOT_FOUND") { repository.moveFolder(context, "missing", null) }
        assertEquals(before, dao.folders())
        repository.moveFolder(context, subtree, chain[5]) // Six ancestor levels + two subtree levels.
        assertEquals(chain[5], dao.folder(subtree)!!.parentId)
    }

    @Test fun deletingFolderPromotesChildrenAndActiveAndArchivedConversations() = runBlocking {
        val parent = folder("Parent")
        val target = folder("Target", parent)
        val child = folder("Child", target)
        val leaf = folder("Leaf", child)
        repository.ensureConversations(context, listOf("existing", "active", "archived", "descendant"))
        repository.moveConversation(context, "existing", parent)
        repository.moveConversation(context, "active", target)
        repository.moveConversation(context, "archived", target)
        repository.archiveConversation(context, "archived", now = 123L)
        repository.moveConversation(context, "descendant", leaf)
        repository.deleteFolder(context, target)
        assertNull(dao.folder(target))
        assertEquals(parent, dao.folder(child)!!.parentId)
        assertEquals(child, dao.folder(leaf)!!.parentId)
        assertEquals(parent, dao.extension("active")!!.folderId)
        assertEquals(parent, dao.extension("archived")!!.folderId)
        assertEquals(123L, dao.extension("archived")!!.archivedAt)
        assertEquals(leaf, dao.extension("descendant")!!.folderId)
        assertEquals(4, dao.extensions().size)
        assertTrue(dao.extension("active")!!.folderSortOrder > dao.extension("existing")!!.folderSortOrder)
        assertTrue(dao.extension("archived")!!.folderSortOrder > dao.extension("active")!!.folderSortOrder)
        repository.deleteFolder(context, parent)
        assertNull(dao.folder(child)!!.parentId)
        assertNull(dao.extension("active")!!.folderId)
        assertNull(dao.extension("archived")!!.folderId)
    }

    @Test fun promotionNameConflictRollsBackAllChanges() = runBlocking {
        folder("Shared")
        val target = folder("Target")
        folder("Shared", target)
        repository.ensureConversations(context, listOf("chat"))
        repository.moveConversation(context, "chat", target)
        val folders = dao.folders()
        val members = dao.extensions()
        fails("DUPLICATE_NAME") { repository.deleteFolder(context, target) }
        assertEquals(folders, dao.folders())
        assertEquals(members, dao.extensions())
    }

    @Test fun deletingSameNamedParentAllowsChildToTakeItsNameAtParentLevel() = runBlocking {
        val parent = folder("Same")
        val child = folder("Same", parent)
        repository.deleteFolder(context, parent)
        assertEquals(listOf(child), dao.folders().map { it.id })
        assertNull(dao.folder(child)!!.parentId)
    }

    @Test fun deletedTargetsAndUnknownConversationsAreRejected() = runBlocking {
        val target = folder("Target")
        val snapshot = dao.folder(target)!!.toModel()
        repository.ensureConversations(context, listOf("chat"))
        repository.deleteFolder(context, target)
        fails("NOT_FOUND") { repository.moveConversation(context, "chat", target) }
        fails("CONVERSATION_NOT_FOUND") { repository.moveConversation(context, "missing", null) }
        fails("NOT_FOUND") { repository.renameFolder(context, snapshot, "Old") }
        fails("NOT_FOUND") { repository.deleteFolder(context, target) }
        assertNull(dao.extension("chat")!!.folderId)
    }

    @Test fun concurrentRootCreatesOnlyAllowOneMatchingName() = runBlocking {
        val results = coroutineScope {
            (1..8).map { async(Dispatchers.IO) { runCatching { folder("Shared") } } }.awaitAll()
        }
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(7, results.count { (it.exceptionOrNull() as? ConversationFolderException)?.code == "DUPLICATE_NAME" })
        assertEquals(1, dao.folders().size)
    }

    @Test fun contextWindowOverridePersistsAndCanReturnToModelDefault() = runBlocking {
        repository.ensureConversations(context, listOf("chat"))
        assertNull(repository.contextWindowOverride(context, "chat"))
        repository.setContextWindowOverride(context, "chat", 256_000, now = 20L)
        assertEquals(256_000, repository.contextWindowOverride(context, "chat"))
        repository.setContextWindowOverride(context, "chat", null, now = 30L)
        assertNull(repository.contextWindowOverride(context, "chat"))
    }
}
