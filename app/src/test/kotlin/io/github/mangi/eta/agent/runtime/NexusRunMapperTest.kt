package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.prompt.PromptRunScope
import io.github.zhh_lgtm.nexus.kernel.api.RunMode
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusRunMapperTest {
    @Test fun workflowModeAndStableMeetingSessionReachTheCanonicalRequest() {
        fun workflow(mode: String) = request(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE).let { legacy ->
            legacy.copy(handoff = legacy.handoff!!.copy(payload = AgentUiHandoffPayload("chat-1", workflowMode = mode).toJson()))
        }
        assertEquals(RunMode.PLAN, NexusRunMapper.fromLegacy(workflow("PLAN")).request.mode)
        assertEquals(RunSource.GOAL, NexusRunMapper.fromLegacy(workflow("GOAL")).request.source)
        assertEquals(RunMode.GOAL, NexusRunMapper.fromLegacy(workflow("GOAL")).request.mode)
        val meeting = request(AgentRuntimeWire.MEETING_HANDOFF_SOURCE).copy(modelSessionId = "meeting:room-1:host")
        val first = NexusRunMapper.fromLegacy(meeting).request
        val second = NexusRunMapper.fromLegacy(meeting.copy(runId = "run-2")).request
        assertEquals(first.sessionId, second.sessionId)
        org.junit.Assert.assertNotEquals(first.runId, second.runId)
    }

    @Test fun arbitraryLegacyNamesDoNotCrashOrCollapseIntoOneCanonicalId() {
        val legacy = request("meeting").copy(runId = "meeting-软件工程师-1", modelSessionId = "room:我的会议")
        val first = NexusRunMapper.fromLegacy(legacy).request
        val repeated = NexusRunMapper.fromLegacy(legacy).request
        val other = NexusRunMapper.fromLegacy(legacy.copy(runId = "meeting-资料专家-1")).request
        assertEquals(first.runId, repeated.runId)
        org.junit.Assert.assertNotEquals(first.runId, other.runId)
        assertTrue(first.runId.value.matches(Regex("[A-Za-z0-9._:-]+")))
    }

    private fun request(source: String, scope: PromptRunScope = PromptRunScope()) = AgentRuntimeWire.RunRequest(
        runId = "run-1",
        prompt = "test",
        config = AgentModelClient.ModelConfig(
            baseUrl = "https://fixture.invalid/v1",
            apiKey = "fixture",
            model = "fixture",
            systemPrompt = "fixture",
        ),
        images = emptyList(),
        promptScope = scope,
        handoff = AgentRuntimeWire.EntryHandoff(id = "handoff", source = source, payload = "task-1"),
    )

    @Test fun mapsAllCurrentRuntimeSourcesToCanonicalModes() {
        assertEquals(RunSource.CHAT, NexusRunMapper.fromLegacy(request(AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE)).request.source)
        assertEquals(RunSource.MEETING_HOST, NexusRunMapper.fromLegacy(request(AgentRuntimeWire.MEETING_HANDOFF_SOURCE)).request.source)
        assertEquals(
            RunSource.MEETING_PARTICIPANT,
            NexusRunMapper.fromLegacy(request(AgentRuntimeWire.MEETING_HANDOFF_SOURCE, PromptRunScope(meetingParticipant = "reviewer"))).request.source,
        )
        assertEquals(RunSource.AUTOMATION, NexusRunMapper.fromLegacy(request("scheduled_task")).request.source)
        assertEquals(RunSource.A2A_INBOUND, NexusRunMapper.fromLegacy(request("a2a")).request.source)
        assertEquals(RunMode.TASK, NexusRunMapper.fromLegacy(request("scheduled_task")).request.mode)
        assertEquals("task-1", NexusRunMapper.fromLegacy(request("scheduled_task")).request.taskId?.value)
    }

    @Test fun canonicalBudgetIsAlwaysBounded() {
        val budget = NexusRunMapper.fromLegacy(request("a2a")).request.budget
        assertTrue(budget.hardRounds != null && budget.hardRounds!! > 0)
        assertTrue(budget.hardDurationMs != null && budget.hardDurationMs!! > 0)
        assertTrue(budget.hardToolCalls != null && budget.hardToolCalls!! > 0)
    }
}
