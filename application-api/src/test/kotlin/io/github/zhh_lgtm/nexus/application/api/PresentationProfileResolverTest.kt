package io.github.zhh_lgtm.nexus.application.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationProfileResolverTest {
    @Test
    fun omniFallsBackToClassicAndUnknownProfileFailsSafely() {
        assertEquals("classic", PresentationProfileResolver.forSkin("omni").fallbackProfileId)
        val unknown = PresentationProfileResolver.forSkin("third-party")
        assertEquals("classic", unknown.id)
        assertTrue("unknown-profile-fallback" in unknown.capabilities)
    }
}
