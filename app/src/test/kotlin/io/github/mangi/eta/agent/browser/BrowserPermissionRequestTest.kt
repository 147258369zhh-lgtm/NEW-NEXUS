package io.github.mangi.eta.agent.browser

import android.app.Application
import android.net.Uri
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BrowserPermissionRequestTest {
    @Test
    fun mediaRequestsWithoutTheVisibleUserWindowAreDenied() {
        val session = AgentBrowserSession()
        val request = MediaRequest("https://login.example")
        chrome(session).onPermissionRequest(request)
        assertTrue(request.denied)
        assertFalse(request.granted)
    }

    @Test
    fun exactOriginRequiresUserDecisionAndOtherOriginsRemainDenied() {
        val context = RuntimeEnvironment.getApplication()
        val session = AgentBrowserSession()
        val host = PromptHost()
        val container = FrameLayout(context)
        session.bindInteractionHost(host)
        session.setHostForeground(host, true)
        session.attachTo(container, context)
        AgentBrowserSession::class.java.getDeclaredField("currentUrl").apply { isAccessible = true }
            .set(session, "https://login.example/account")
        try {
            val allowed = MediaRequest("https://login.example")
            chrome(session).onPermissionRequest(allowed)
            assertSame(allowed, host.media)
            assertFalse(allowed.granted)
            val thirdParty = MediaRequest("https://embedded.other.example")
            chrome(session).onPermissionRequest(thirdParty)
            assertTrue(thirdParty.denied)
            val unknown = MediaRequest("https://login.example", arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
            chrome(session).onPermissionRequest(unknown)
            assertTrue(unknown.denied)
            session.setHostForeground(host, false)
            assertTrue(allowed.denied)
        } finally {
            session.detachFrom(container)
            session.unbindInteractionHost(host)
        }
    }

    private fun chrome(session: AgentBrowserSession): WebChromeClient {
        val type = AgentBrowserSession::class.java.declaredClasses.single { it.simpleName == "BrowserChrome" }
        return type.getDeclaredConstructor(AgentBrowserSession::class.java).apply { isAccessible = true }
            .newInstance(session) as WebChromeClient
    }

    private class MediaRequest(
        private val origin: String,
        private val resources: Array<String> = arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE),
    ) : PermissionRequest() {
        var denied = false
        var granted = false
        override fun getOrigin(): Uri = Uri.parse(origin)
        override fun getResources(): Array<String> = resources
        override fun grant(resources: Array<out String>) { granted = true }
        override fun deny() { denied = true }
    }

    private class PromptHost : BrowserUserInteractionHost {
        var media: PermissionRequest? = null
        override fun chooseFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams) {
            callback.onReceiveValue(null)
        }
        override fun requestMediaPermission(request: PermissionRequest) { media = request }
        override fun cancelMediaPermission(request: PermissionRequest?) {
            if (request == null || request === media) { media?.deny(); media = null }
        }
        override fun cancelPageRequests() { cancelMediaPermission() }
    }
}
