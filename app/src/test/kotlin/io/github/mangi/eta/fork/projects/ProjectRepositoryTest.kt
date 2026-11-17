package io.github.mangi.eta.fork.projects

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProjectRepositoryTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ProjectDatabase.closeForTests()
        context.deleteDatabase("eta_projects.db")
    }

    @After fun tearDown() {
        ProjectDatabase.closeForTests()
        context.deleteDatabase("eta_projects.db")
    }

    @Test fun projectCanBindAndReplaceConversationOrMeetingSubjects() = runBlocking {
        val project = ProjectRepository.create(context, draft("content:root", "Root"), now = 10)
        ProjectRepository.bindSubject(context, project, ProjectSubjectType.CONVERSATION, "chat-1", now = 11)
        assertEquals(project, ProjectRepository.bindingForSubject(context, ProjectSubjectType.CONVERSATION, "chat-1")?.projectId)
        ProjectRepository.bindSubject(context, project, ProjectSubjectType.MEETING, "room-1", now = 12)
        assertEquals(project, ProjectRepository.bindingForSubject(context, ProjectSubjectType.MEETING, "room-1")?.projectId)
        ProjectRepository.unbindSubject(context, ProjectSubjectType.CONVERSATION, "chat-1")
        assertNull(ProjectRepository.bindingForSubject(context, ProjectSubjectType.CONVERSATION, "chat-1"))
    }

    @Test fun duplicateRootAndArchivedProjectAreRejected() = runBlocking {
        ProjectRepository.create(context, draft("logical:one", "One"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ProjectRepository.create(context, draft("logical:one", "Again")) }
        }
        val project = ProjectRepository.projects(context).first().single().id
        ProjectRepository.setArchived(context, project, archived = true)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ProjectRepository.bindSubject(context, project, ProjectSubjectType.CONVERSATION, "chat") }
        }
        Unit
    }

    @Test fun selectorShowsProjectAndRejectsStaleOrDeletedConversation() = runBlocking {
        val conversationId = "selector-${java.util.UUID.randomUUID()}"
        val conversations = EtaDatabase.get(context).conversationDao()
        conversations.insertConversations(listOf(ConversationEntity(
            id = conversationId, title = "设计讨论", thinkingEnabled = false, createdAt = 10, updatedAt = 20,
        )))
        val firstProject = ProjectRepository.create(context, draft("logical:selector-first", "First"))
        val secondProject = ProjectRepository.create(context, draft("logical:selector-second", "Second"))
        val unbound = ProjectRepository.subjectOptions(context).single { it.id == conversationId }
        assertNull(unbound.projectId)
        ProjectRepository.bindSubjectIfUnchanged(context, firstProject, unbound)
        val bound = ProjectRepository.subjectOptions(context).single { it.id == conversationId }
        assertEquals("设计讨论", bound.title)
        assertEquals("First", bound.projectName)
        val stale = assertThrows(ProjectException::class.java) {
            runBlocking { ProjectRepository.bindSubjectIfUnchanged(context, secondProject, unbound) }
        }
        assertEquals("BINDING_CHANGED", stale.code)
        assertEquals(firstProject, ProjectRepository.bindingForSubject(context, ProjectSubjectType.CONVERSATION, conversationId)?.projectId)
        ProjectRepository.bindSubjectIfUnchanged(context, secondProject, bound)
        assertEquals(secondProject, ProjectRepository.bindingForSubject(context, ProjectSubjectType.CONVERSATION, conversationId)?.projectId)
        val missing = bound.copy(id = "missing-$conversationId", projectId = null)
        val deleted = assertThrows(ProjectException::class.java) {
            runBlocking { ProjectRepository.bindSubjectIfUnchanged(context, firstProject, missing) }
        }
        assertEquals("SUBJECT_NOT_FOUND", deleted.code)
    }

    @Test fun safProjectCreationRequiresTreeUriAndDoesNotPersistFileDocumentIdentity() = runBlocking {
        val document = Uri.parse("content://provider.example/document/one")
        val failure = assertThrows(ProjectException::class.java) {
            runBlocking { ProjectRepository.create(context, ProjectDraft("Bad", "app", document.toString(), "file", "SAF")) }
        }
        assertEquals("ROOT_INVALID", failure.code)
        assertNull(ProjectRepository.projects(context).first().firstOrNull())

        val tree = DocumentsContract.buildTreeDocumentUri("provider.example", "root")
        val grantFailure = assertThrows(IllegalArgumentException::class.java) {
            ProjectRepository.validateSafRoot(tree, hasReadWriteGrant = false)
        }
        assertEquals("ROOT_PERMISSION_REQUIRED", grantFailure.message)
        ProjectRepository.validateSafRoot(tree, hasReadWriteGrant = true)
    }

    private fun draft(root: String, name: String) = ProjectDraft(
        name = name,
        providerId = "app",
        rootIdentityKey = root,
        rootDisplayName = name,
        source = "APP_WORKSPACE",
    )
}
