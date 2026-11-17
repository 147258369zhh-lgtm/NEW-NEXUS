package io.github.mangi.eta.agent.voice

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import io.github.mangi.eta.core.ModuleConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36], application = EtaAssistantEntryTest.TestApplication::class)
class EtaAssistantEntryTest {
    class TestApplication : Application() {
        var rejectServiceStart = false

        override fun startService(service: Intent): ComponentName? {
            if (rejectServiceStart) throw IllegalStateException("Background service launch rejected")
            return super.startService(service)
        }
    }

    private val context get() = RuntimeEnvironment.getApplication() as TestApplication

    // A bound framework VoiceInteractionManager is intentionally absent in these
    // cold-start tests; also keep the static reference isolated between tests.
    private fun setActiveService(service: EtaVoiceInteractionService?) {
        EtaVoiceInteractionService::class.java.getDeclaredField("activeService").apply {
            isAccessible = true
            set(null, service)
        }
    }

    @Before fun reset() {
        setActiveService(null)
        Settings.Secure.putString(context.contentResolver, ModuleConfig.SECURE_ASSISTANT, "")
        Settings.Secure.putString(context.contentResolver, ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE, "")
        ShadowSettings.setCanDrawOverlays(false)
        context.rejectServiceStart = false
    }

    @After fun clearService() = setActiveService(null)

    @Test fun configuredComponentResolvesToTheRealServiceAndAssistBridgeInManifest() {
        val service = ComponentName(context, EtaVoiceInteractionService::class.java)
        val activity = ComponentName(context, EtaVoiceAssistActivity::class.java)
        // Compare to actual classes and merged manifest declarations, not to a
        // second constant containing the same possibly misspelled class name.
        assertEquals(service.flattenToString(), ModuleConfig.ETA_VOICE_INTERACTION_COMPONENT)
        val serviceInfo = context.packageManager.getServiceInfo(service, 0)
        val activityInfo = context.packageManager.getActivityInfo(activity, 0)
        assertEquals(EtaVoiceInteractionService::class.java.name, serviceInfo.name)
        assertEquals(EtaVoiceAssistActivity::class.java.name, activityInfo.name)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", serviceInfo.permission)
        assertEquals("${context.packageName}:voice", serviceInfo.processName)
        assertEquals(serviceInfo.processName, activityInfo.processName)
    }

    @Test fun defaultAssistantRecognizesTheServiceOrAssistActivityButNotAnotherApp() {
        val service = ComponentName(context, EtaVoiceInteractionService::class.java).flattenToString()
        val activity = ComponentName(context, EtaVoiceAssistActivity::class.java).flattenToShortString()
        Settings.Secure.putString(context.contentResolver, ModuleConfig.SECURE_ASSISTANT, service)
        assertTrue(EtaVoiceInteractionService.isDefaultAssistant(context))
        Settings.Secure.putString(context.contentResolver, ModuleConfig.SECURE_ASSISTANT, activity)
        assertTrue(EtaVoiceInteractionService.isDefaultAssistant(context))
        Settings.Secure.putString(context.contentResolver, ModuleConfig.SECURE_ASSISTANT, "other.app/other.app.Assist")
        assertFalse(EtaVoiceInteractionService.isDefaultAssistant(context))
        Settings.Secure.putString(context.contentResolver, ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE, service)
        assertTrue(EtaVoiceInteractionService.isDefaultAssistant(context))
    }

    @Test fun missingOrUnboundSessionReturnsFalseInsteadOfCrashing() {
        assertFalse(EtaVoiceInteractionService.requestSession())
        // Framework showSession throws before onReady supplies its manager.
        setActiveService(EtaVoiceInteractionService())
        assertFalse(EtaVoiceInteractionService.requestSession())
    }

    @Test fun coldAssistLaunchStartsTheExistingOverlayWithoutScreenContext() {
        ShadowSettings.setCanDrawOverlays(true)
        val controller = Robolectric.buildActivity(EtaVoiceAssistActivity::class.java).create()
        val started = checkNotNull(shadowOf(context).nextStartedService)
        assertEquals(ComponentName(context, EtaAssistantOverlayService::class.java), started.component)
        assertNull(started.getStringExtra("assistant_screen_context_id"))
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun missingOverlayPermissionOpensTheExistingAndroidSettingsPage() {
        val controller = Robolectric.buildActivity(EtaVoiceAssistActivity::class.java).create()
        val started = checkNotNull(shadowOf(controller.get()).nextStartedActivity)
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, started.action)
        assertEquals("package:${context.packageName}", started.dataString)
        assertNull(shadowOf(context).nextStartedService)
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun failedSessionRequestAlsoFallsBackToTheExistingOverlay() {
        ShadowSettings.setCanDrawOverlays(true)
        setActiveService(EtaVoiceInteractionService())
        val controller = Robolectric.buildActivity(EtaVoiceAssistActivity::class.java).create()
        val started = checkNotNull(shadowOf(context).nextStartedService)
        assertEquals(ComponentName(context, EtaAssistantOverlayService::class.java), started.component)
        assertNull(started.getStringExtra("assistant_screen_context_id"))
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun rejectedOverlayLaunchDoesNotCrashTheAssistBridge() {
        ShadowSettings.setCanDrawOverlays(true)
        context.rejectServiceStart = true
        val controller = Robolectric.buildActivity(EtaVoiceAssistActivity::class.java).create()
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun sessionWithoutOverlayPermissionReleasesContextEvenWithoutAUsableToken() {
        val session = EtaVoiceInteractionSession(context)
        session.onPrepareShow(null, 0)
        val capture = EtaVoiceInteractionSession::class.java.getDeclaredField("screenContext").let {
            it.isAccessible = true
            it.get(session) as EtaAssistantScreenContext
        }
        // Both startAssistantActivity and hide require a real system session
        // token, absent here. Permission handling must still clean up safely.
        session.onShow(null, 0)
        assertNull(EtaAssistantScreenContexts.find(capture.id))
        assertNull(shadowOf(context).nextStartedService)
        session.onHide()
    }
}
