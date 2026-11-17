package io.github.mangi.eta.hook.system

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import io.github.mangi.eta.agent.voice.EtaVoiceAssistActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36], application = Application::class)
class NexaAssistantGestureInvokerTest {
    private class GestureContext(base: Context) : ContextWrapper(base) {
        val starts = mutableListOf<Intent>()
        var launchFailure: RuntimeException? = null

        override fun startActivity(intent: Intent) {
            starts += Intent(intent)
            launchFailure?.let { throw it }
        }
    }

    private fun gestureContext() = GestureContext(RuntimeEnvironment.getApplication())

    @Test
    fun acceptedSystemSessionDoesNotLaunchAnotherActivity() {
        val context = gestureContext()

        assertTrue(NexaAssistantGestureInvoker.launch(context, requestSession = { true }))

        assertTrue(context.starts.isEmpty())
    }

    @Test
    fun failedSessionOpensOnlyTheExistingAssistBridgeWithoutSubmissionOrScreenData() {
        val context = gestureContext()

        assertTrue(NexaAssistantGestureInvoker.launch(context, requestSession = { false }))

        val intent = context.starts.single()
        val expected = ComponentName(context, EtaVoiceAssistActivity::class.java)
        assertEquals(expected, intent.component)
        assertEquals(Intent.ACTION_ASSIST, intent.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertNull(intent.extras)
        assertNull(intent.data)
        assertNull(intent.clipData)
        val activity = context.packageManager.getActivityInfo(expected, 0)
        assertTrue(activity.exported)
        assertTrue(activity.enabled)
    }

    @Test
    fun unprivilegedColorDirectCallerFallsBackAfterSessionPermissionDenial() {
        val context = gestureContext()
        val failures = mutableListOf<Throwable>()

        assertTrue(
            NexaAssistantGestureInvoker.launch(
                context,
                requestSession = { throw SecurityException("Privileged session API") },
                onFailure = failures::add,
            ),
        )

        assertEquals(1, context.starts.size)
        assertEquals(1, failures.size)
        assertTrue(failures.single() is SecurityException)
    }

    @Test
    fun missingNexaOrDeniedActivityStartLeavesTheGestureUnhandled() {
        for (failure in listOf(
            ActivityNotFoundException("NEXA absent or disabled"),
            SecurityException("Activity start rejected"),
        )) {
            val context = gestureContext().apply { launchFailure = failure }
            val failures = mutableListOf<Throwable>()

            assertFalse(
                NexaAssistantGestureInvoker.launch(
                    context,
                    requestSession = { false },
                    onFailure = failures::add,
                ),
            )

            assertEquals(listOf(failure), failures)
            assertEquals(1, context.starts.size)
        }
    }
}
