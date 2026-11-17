package io.github.mangi.eta.data.repository

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MemoryManagementCatalogColdStartTest {
    @Test fun memoryCatalogDoesNotRequireOpeningTheCharacterLibraryFirst() = runBlocking(Dispatchers.IO) {
        val app = RuntimeEnvironment.getApplication()
        AgentMemoryRepository.init(app)
        ReflectionHelpers.setStaticField(CharacterRepository::class.java, "context", null)
        val targets = MemoryManagementCatalog.load(app)
        assertTrue(targets.scopes.any { it == AgentMemoryScope.GLOBAL })
        assertNotNull(AgentMemoryRepository.snapshot(AgentMemoryScope.GLOBAL))
        assertNotNull(CharacterRepository.list())
    }
}
