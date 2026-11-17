package io.github.mangi.eta.agent.browser

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BrowserInteractionPolicyTest {
    @Test
    fun mediaPermissionsAreRestrictedToTheExactSecureOrigin() {
        assertTrue(BrowserInteractionPolicy.sameSecureOrigin("https://example.com/login", "https://EXAMPLE.com:443/"))
        assertFalse(BrowserInteractionPolicy.sameSecureOrigin("http://example.com", "http://example.com"))
        assertFalse(BrowserInteractionPolicy.sameSecureOrigin("https://example.com", "https://example.com.evil.test"))
        assertFalse(BrowserInteractionPolicy.sameSecureOrigin("https://example.com", "https://example.com:8443"))
        assertFalse(BrowserInteractionPolicy.sameSecureOrigin("https://example.com", "https://user@example.com"))
        assertFalse(BrowserInteractionPolicy.sameSecureOrigin("", "https://example.com"))
    }

    @Test
    fun scannedCodesCannotOpenScriptsLocalFilesOrApplicationIntents() {
        assertEquals("https://example.com/login", BrowserInteractionPolicy.confirmedQrWebAddress("  https://example.com/login  "))
        listOf("javascript:alert(1)", "file:///data/data/private", "content://private/file", "intent://pay#Intent;end",
            "https://user:secret@example.com", "https://", "https://example.com\n.evil.test", "https://example.com:99999")
            .forEach { assertNull(it, BrowserInteractionPolicy.confirmedQrWebAddress(it)) }
    }
}
