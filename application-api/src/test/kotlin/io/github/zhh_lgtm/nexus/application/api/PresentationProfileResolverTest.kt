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

    @Test
    fun auroraIsAFirstClassProfileWithClassicFallback() {
        val aurora = PresentationProfileResolver.forSkin("aurora")
        assertEquals("aurora", aurora.id)
        assertEquals("classic", aurora.fallbackProfileId)
        assertTrue("material-motion" in aurora.capabilities)
    }

    @Test
    fun glassProfilesRemainAvailableToTheSharedPresentationLayer() {
        for (id in listOf("frost_glass", "liquid_glass")) {
            val profile = PresentationProfileResolver.forSkin(" $id ")
            assertEquals(id, profile.id)
            assertEquals("classic", profile.fallbackProfileId)
            assertTrue("glass" in profile.capabilities)
        }
    }
}
