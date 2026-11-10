package io.github.mangi.eta.fork.projects

import android.content.Context
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

    private fun draft(root: String, name: String) = ProjectDraft(
        name = name,
        providerId = "app",
        rootIdentityKey = root,
        rootDisplayName = name,
        source = "APP_WORKSPACE",
    )
}
