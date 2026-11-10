package io.github.mangi.eta.fork.meeting

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.*
import org.junit.Test

class MeetingToolActivityTest {
    @Test fun traceIdentitySeparatesRunsRoundsHostedCallsAndDelimiterCharacters() {
        val ids = listOf(
            MeetingToolActivity.idFor("run:a", 1, "b", false),
            MeetingToolActivity.idFor("run", 1, "a:b", false),
            MeetingToolActivity.idFor("run:a", 2, "b", false),
            MeetingToolActivity.idFor("run:a", 1, "b", true),
        )
        assertEquals(4, ids.distinct().size)
        assertEquals(ids[0], MeetingToolActivity.idFor("run:a", 1, "b", false))
    }

    @Test fun durableNormalTraceRedactsCommandsHeadersAndJsonBeforeApplyingSizeLimits() {
        val tool = MeetingToolActivity("run", 1, "call", false, "run_command", MeetingToolPhase.SUCCESS,
            arguments = "password=argument-secret", command = "curl -H 'Authorization: Bearer header-secret' --token flag-secret",
            result = "{\"api_key\":\"json-secret\"}\nBearer plain-secret TOKEN=assignment-secret").durable()
        val encoded = tool.encode(false)
        listOf("argument-secret", "header-secret", "flag-secret", "json-secret", "plain-secret", "assignment-secret").forEach {
            assertFalse("Credential must be absent from durable metadata", encoded.contains(it))
        }
        assertTrue(encoded.contains("已隐藏"))
        val long = tool.copy(arguments = "x".repeat(8_000), result = "y".repeat(20_000)).durable()
        assertEquals(2_000, long.arguments.length)
        assertEquals(4_000, long.result!!.length)
        assertTrue(long.result.endsWith("…"))
        assertEquals(long, MeetingToolActivity.decode(long.encode(true)))
    }

    @Test fun sensitiveToolPreviewsAndNestedSubagentProgressNeverEnterTheArchive() {
        listOf("mcp_remote_query", "read_sms_code", "memory_get").forEach { name ->
            val tool = MeetingToolActivity("run", 1, "call", false, name, MeetingToolPhase.SUCCESS,
                arguments = "private arguments", command = "private command", result = "private result", imageCount = 2).durable()
            assertEquals("", tool.arguments)
            assertNull(tool.command)
            assertNull(tool.result)
            assertEquals(2, tool.imageCount)
            assertFalse(tool.encode(false).contains("private"))
        }
        val subagent = MeetingToolActivity("run", 1, "call", false, "subagent", MeetingToolPhase.RUNNING,
            command = "{\"nested_progress\":\"private\"}").durable()
        assertNull(subagent.command)
    }

    @Test fun legacyFinishWithoutAnExplicitSuccessCannotBeShownAsConfirmedSuccess() {
        fun finished(summary: String) = AgentEvent.ToolFinished(1, "call", "read_file", summary, 0, 0, success = null)
        assertEquals(MeetingToolPhase.UNKNOWN, MeetingToolActivity.fromEvent("run", finished("some output"), null)!!.phase)
        assertEquals(MeetingToolPhase.FAILED, MeetingToolActivity.fromEvent("run", finished("ok=false"), null)!!.phase)
    }

    @Test fun invalidOrFutureMetadataIsRejectedForReadableLegacyFallback() {
        assertNull(MeetingToolActivity.decode("{broken"))
        val payload = MeetingToolActivity("run", 1, "call", false, "read_file", MeetingToolPhase.SUCCESS).encode(false)
        assertNull(MeetingToolActivity.decode(payload.replace("\"version\":1", "\"version\":2")))
        assertNull(MeetingToolActivity.decode(payload.replace("\"phase\":\"success\"", "\"phase\":\"invented\"")))
    }
}
