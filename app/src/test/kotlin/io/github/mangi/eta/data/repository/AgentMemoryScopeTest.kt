package io.github.mangi.eta.data.repository

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentMemoryScopeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var context: Context

    @Before
    fun reset() {
        val files = temporaryFolder.newFolder()
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = files
        }
        AgentMemoryRepository.init(context)
        AgentMemoryRepository.replaceAll("")
        AgentMemoryRepository.clearScopedScopes()
    }

    @Test
    fun projectAndConversationMemoriesArePhysicallyIsolated() {
        val project = AgentMemoryScope.project("project_a")
        val conversation = AgentMemoryScope.conversation("conversation_a")
        AgentMemoryRepository.replaceAll(project, "项目事实")
        AgentMemoryRepository.replaceAll(conversation, "会话事实")

        assertEquals("项目事实", AgentMemoryRepository.snapshot(project).content)
        assertEquals("会话事实", AgentMemoryRepository.snapshot(conversation).content)
        assertEquals("", AgentMemoryRepository.snapshot().content)
        assertNotEquals(AgentMemoryRepository.snapshot(project).revision, AgentMemoryRepository.snapshot(conversation).revision)
    }

    @Test
    fun invalidScopeIdsCannotEscapeScopeDirectory() {
        assertThrows(IllegalArgumentException::class.java) { AgentMemoryScope.project("../outside") }
        assertThrows(IllegalArgumentException::class.java) { AgentMemoryScope.conversation("a/b") }
    }
}
