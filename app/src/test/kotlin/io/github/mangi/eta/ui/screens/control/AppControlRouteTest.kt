package io.github.mangi.eta.ui.screens.control

import io.github.mangi.eta.ui.navigation.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppControlRouteTest {
    @Test
    fun staticRoutesCoverControlAndKnowledgeAreas() {
        assertEquals(AppRoute.SharedFolders, appControlRoute("shared_folders"))
        assertEquals(AppRoute.SpeechRecognition, appControlRoute("speech_recognition"))
        assertEquals(AppRoute.SpeechSynthesis, appControlRoute("speech_synthesis"))
        assertEquals(AppRoute.SelfLearning, appControlRoute("learning"))
        assertEquals(AppRoute.ApiConnectors, appControlRoute("api_connectors"))
    }

    @Test
    fun dynamicRoutesPreserveStableIdentifiers() {
        assertEquals(AppRoute.ModelProviderDetail("provider-a"), appControlRoute("model_provider:provider-a"))
        assertEquals(AppRoute.McpServerDetail("server-a"), appControlRoute("mcp_server:server-a"))
        assertEquals(AppRoute.MeetingRoom("room-a"), appControlRoute("meeting_room:room-a"))
        assertNull(appControlRoute("model_provider:"))
        assertNull(appControlRoute("unknown-route"))
    }
}
