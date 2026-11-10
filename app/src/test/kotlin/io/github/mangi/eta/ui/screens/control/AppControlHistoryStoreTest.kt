package io.github.mangi.eta.ui.screens.control

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppControlHistoryStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun historyRoundTripsAndKeepsNewestEightyMessages() {
        val store = AppControlHistoryStore(context)
        val messages = (0 until 90).map { index ->
            AppControlMessage(
                id = "message-$index",
                role = if (index % 2 == 0) AppControlMessageRole.USER else AppControlMessageRole.ASSISTANT,
                text = "body $index",
            )
        }

        store.save(messages)
        val restored = store.load()

        assertEquals(80, restored.size)
        assertEquals("message-10", restored.first().id)
        assertEquals("message-89", restored.last().id)
        assertEquals(AppControlMessageRole.ASSISTANT, restored.last().role)
    }
}
