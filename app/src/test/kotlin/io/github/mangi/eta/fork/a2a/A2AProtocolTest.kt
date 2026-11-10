package io.github.mangi.eta.fork.a2a

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class A2AProtocolTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Test
    fun `wire enum values follow A2A 1_0 names`() {
        val encoded = json.encodeToString(A2ATaskStatus(A2ATaskState.INPUT_REQUIRED))
        assertEquals("""{"state":"TASK_STATE_INPUT_REQUIRED"}""", encoded)

        val decoded = json.decodeFromString<A2ATaskStatus>(
            """{"state":"TASK_STATE_AUTH_REQUIRED"}""",
        )
        assertEquals(A2ATaskState.AUTH_REQUIRED, decoded.state)
    }

    @Test
    fun `stream response rejects zero or multiple variants`() {
        assertThrows(IllegalArgumentException::class.java) {
            A2AStreamResponse()
        }
        assertThrows(IllegalArgumentException::class.java) {
            A2AStreamResponse(
                message = textMessage(),
                statusUpdate = A2ATaskStatusUpdateEvent(
                    taskId = "task",
                    contextId = "context",
                    status = A2ATaskStatus(A2ATaskState.WORKING),
                ),
            )
        }
    }

    @Test
    fun `send response decodes task payload`() {
        val response = json.decodeFromString<A2ASendMessageResponse>(
            """
            {
              "task": {
                "id": "task-1",
                "contextId": "context-1",
                "status": {"state": "TASK_STATE_COMPLETED"},
                "artifacts": [{
                  "artifactId": "artifact-1",
                  "parts": [{"text": "done"}]
                }]
              }
            }
            """.trimIndent(),
        )
        assertEquals("task-1", response.task?.id)
        assertEquals(A2ATaskState.COMPLETED, response.task?.status?.state)
    }

    @Test
    fun `metadata remains arbitrary json`() {
        val request = json.parseToJsonElement(
            """{"owner":"room","attempt":2,"nested":{"ok":true}}""",
        ).jsonObject
        val message = A2AMessage(
            messageId = "message-1",
            role = A2ARole.USER,
            parts = listOf(A2APart(text = "hello")),
            metadata = request,
        )
        assertEquals(request, message.metadata)
    }

    private fun textMessage() = A2AMessage(
        messageId = "message",
        role = A2ARole.AGENT,
        parts = listOf(A2APart(text = "hello")),
    )
}
