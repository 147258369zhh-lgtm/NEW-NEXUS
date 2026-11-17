package io.github.mangi.eta.fork.meeting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.mangi.eta.agent.subagent.SubagentProfile

class MeetingRoomCreationBridgeTest {
    @Test fun everyMeetingPurposeCanStartWithOnlyAHostAndStillResolvesItsModel() {
        val host = MeetingProfileOption("meeting-host", "主持", true)
        val model = MeetingModelOption("p", "Provider", "m", "model", "Model", supportsTools = true)
        for (workflow in listOf(null) + MeetingWorkflowCatalog.all.map { it.id }) {
            val draft = MeetingRoomSelectionDraft("Host only", host.name, emptySet(), templateId = workflow)
            assertTrue(draft.validate())
            assertEquals(setOf(host.name), MeetingRoomCreationBridge.automaticBindings(draft,
                MeetingRoomCreationOptions(listOf(host), listOf(model))).keys)
        }
    }
    @Test
    fun defaultHostHonorsSavedLocalChoiceAndNeverFallsBackToTheFirstExpert() {
        val profiles = MeetingRoomCreationBridge.profileOptions(SubagentProfile.BUILTIN_PROFILES) +
            MeetingProfileOption("my-host", "用户主持", false)
        assertEquals("my-host", MeetingRoomCreationBridge.resolveDefaultHost("my-host", profiles))
        assertEquals("meeting-host", MeetingRoomCreationBridge.resolveDefaultHost("removed-host", profiles.reversed()))
        assertEquals("主持协调", profiles.first { it.name == "meeting-host" }.displayName)
    }

    @Test
    fun automaticHostPrefersStructuredReasoningWhileExplicitBindingsStillWin() {
        val ordinary = MeetingModelOption("p", "Provider", "fast", "fast", "Fast", supportsTools = true)
        val reasoning = ordinary.copy(modelId = "reason", modelName = "reason", supportsReasoning = true, structuredOutput = true)
        val profiles = MeetingRoomCreationBridge.profileOptions(SubagentProfile.BUILTIN_PROFILES)
        val options = MeetingRoomCreationOptions(profiles, listOf(ordinary, reasoning))
        val draft = MeetingRoomSelectionDraft("Review", "meeting-host", setOf("explore"))
        assertEquals("reason", MeetingRoomCreationBridge.automaticBindings(draft, options)["meeting-host"]?.modelId)
        assertEquals("fast", MeetingRoomCreationBridge.automaticBindings(draft.copy(modelOverrides = mapOf("meeting-host" to ordinary)), options)["meeting-host"]?.modelId)
    }

    @Test
    fun remoteAgentsWithTheSameDisplayNameRetainDistinctStableProfiles() {
        val local = MeetingProfileOption("lead", "host", builtIn = true)
        val first = remote(name = "Research · A2A", id = "remote-a")
        val second = remote(name = "Research · A2A", id = "remote-b")

        val merged = MeetingRoomCreationBridge.mergeMeetingProfiles(
            localProfiles = listOf(local),
            remoteProfiles = listOf(first, second),
        )

        assertEquals(3, merged.size)
        assertEquals("lead", merged.first().name)
        assertEquals(listOf("remote-a", "remote-b"), merged.drop(1).map { it.remoteAgentId })
        assertNotEquals(merged[1].name, merged[2].name)
        assertTrue(merged.drop(1).all { it.name.startsWith("Research · A2A · ") })
    }

    @Test
    fun uniqueRemoteIdentityKeepsTheExistingCompatibleProfileName() {
        val remote = remote(name = "Research · A2A", id = "remote-a")

        val merged = MeetingRoomCreationBridge.mergeMeetingProfiles(emptyList(), listOf(remote))

        assertEquals(listOf(remote), merged)
    }

    private fun remote(name: String, id: String) = MeetingProfileOption(
        name = name,
        summary = "https://agent.example/$id",
        builtIn = false,
        remoteAgentId = id,
    )
}
