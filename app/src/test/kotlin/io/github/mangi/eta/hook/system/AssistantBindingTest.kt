package io.github.mangi.eta.hook.system

import io.github.mangi.eta.config.PowerAssistantTarget
import io.github.mangi.eta.core.ModuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantBindingTest {
    @Test
    fun `OEM target has no managed assistant binding`() {
        assertNull(assistantBindingFor(PowerAssistantTarget.OEM))
        assertFalse(
            shouldConfigureAssistant(
                autoConfigEnabled = true,
                target = PowerAssistantTarget.OEM,
            ),
        )
    }

    @Test
    fun `Gemini and Eta use their own packages and components`() {
        val gemini = requireNotNull(assistantBindingFor(PowerAssistantTarget.GEMINI))
        val eta = requireNotNull(assistantBindingFor(PowerAssistantTarget.ETA))

        assertEquals(ModuleConfig.GOOGLE_PACKAGE, gemini.packageName)
        assertEquals(ModuleConfig.GOOGLE_ASSISTANT_COMPONENT, gemini.componentName)
        assertEquals(ModuleConfig.ETA_PACKAGE, eta.packageName)
        assertEquals(ModuleConfig.ETA_VOICE_INTERACTION_COMPONENT, eta.componentName)
    }

    @Test
    fun `automatic configuration requires enabled switch and unchanged target`() {
        assertTrue(
            isAssistantConfigurationCurrent(
                autoConfigEnabled = true,
                expectedTarget = PowerAssistantTarget.GEMINI,
                currentTarget = PowerAssistantTarget.GEMINI,
            ),
        )
        assertFalse(
            isAssistantConfigurationCurrent(
                autoConfigEnabled = false,
                expectedTarget = PowerAssistantTarget.GEMINI,
                currentTarget = PowerAssistantTarget.GEMINI,
            ),
        )
        assertFalse(
            isAssistantConfigurationCurrent(
                autoConfigEnabled = true,
                expectedTarget = PowerAssistantTarget.GEMINI,
                currentTarget = PowerAssistantTarget.ETA,
            ),
        )
    }

    @Test
    fun `automatic configuration never restores OEM without module ownership`() {
        assertEquals(
            AssistantSelectionAction.CONFIGURE_MANAGED,
            assistantSelectionAction(true, PowerAssistantTarget.GEMINI),
        )
        assertEquals(
            AssistantSelectionAction.CONFIGURE_MANAGED,
            assistantSelectionAction(true, PowerAssistantTarget.ETA),
        )
        assertEquals(
            AssistantSelectionAction.NONE,
            assistantSelectionAction(false, PowerAssistantTarget.GEMINI),
        )
        assertEquals(
            AssistantSelectionAction.NONE,
            assistantSelectionAction(false, PowerAssistantTarget.OEM),
        )
        assertEquals(AssistantSelectionAction.NONE, assistantSelectionAction(true, PowerAssistantTarget.OEM))
        assertEquals(
            AssistantSelectionAction.NONE,
            assistantSelectionAction(false, PowerAssistantTarget.OEM, hasManagedOwnership = true),
        )
        assertEquals(
            AssistantSelectionAction.RESTORE_OEM,
            assistantSelectionAction(true, PowerAssistantTarget.OEM, hasManagedOwnership = true),
        )
    }

    @Test
    fun `explicit selection configures once even when automatic repair is disabled`() {
        assertEquals(
            AssistantSelectionAction.CONFIGURE_MANAGED,
            assistantSelectionAction(false, PowerAssistantTarget.ETA, explicitSelection = true),
        )
        assertEquals(
            AssistantSelectionAction.RESTORE_OEM,
            assistantSelectionAction(false, PowerAssistantTarget.OEM, explicitSelection = true),
        )
        assertTrue(isAssistantConfigurationCurrent(false, PowerAssistantTarget.ETA, PowerAssistantTarget.ETA, explicitSelection = true))
        assertFalse(isAssistantConfigurationCurrent(false, PowerAssistantTarget.ETA, PowerAssistantTarget.OEM, explicitSelection = true))
    }

    @Test
    fun `selection requests cannot replay in a different Android user`() {
        assertEquals("0:request", assistantSelectionRequestForUser("0:request", 0))
        assertNull(assistantSelectionRequestForUser("0:request", 10))
        assertEquals("10:request", assistantSelectionRequestForUser("10:request", 10))
        assertNull(assistantSelectionRequestForUser("10:request", 0))
        assertNull(assistantSelectionRequestForUser("10:", 10))
        assertNull(assistantSelectionRequestForUser("", 0))
        assertEquals("legacy-request", assistantSelectionRequestForUser("legacy-request", 0))
        assertNull(assistantSelectionRequestForUser("legacy-request", 10))
    }
}
