package io.github.mangi.eta.ui.components

import android.app.Application
import io.github.mangi.eta.ui.screens.memory.MemoryLearningPresentationTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NexusNotepadPullHandleTest {
    @Test
    fun topHandleHasAccessibleButtonActionAndOpensNotepad() {
        var opened = 0
        MemoryLearningPresentationTest.Panel {
            NexusNotepadPullHandle(onOpen = { opened++ })
        }.use { panel ->
            panel.clickDescription("Open conversation notepad")
            assertEquals(1, opened)
        }
    }
}
