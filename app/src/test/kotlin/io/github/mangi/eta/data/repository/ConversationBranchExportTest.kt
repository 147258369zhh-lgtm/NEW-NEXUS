package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.db.ConversationBranchEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class ConversationBranchExportTest {
    @Test fun graphKeepsExactSourceAndMissingParentWithoutSerializingChatContent() = runBlocking {
        val nodes = listOf(ConversationBranchEntity("child", "original-root", sourceConversationId = "deleted-parent",
            sourceMessageId = "source-id", sourceSortIndex = 99, createdAt = 1))
        val output = ByteArrayOutputStream()
        ConversationBranchExport.writeDocument(output, nodes)
        val document = Json.parseToJsonElement(output.toString("UTF-8")).jsonObject
        assertEquals("eta_conversation_branch_graph", document.getValue("type").jsonPrimitive.content)
        val node = document.getValue("nodes").jsonArray.single().jsonObject
        assertEquals(JsonNull, node.getValue("parentConversationId"))
        assertEquals("deleted-parent", node.getValue("sourceConversationId").jsonPrimitive.content)
        assertEquals(99, node.getValue("sourceSortIndex").jsonPrimitive.int)
        assertFalse(node.containsKey("content"))
        assertFalse(node.containsKey("title"))
    }
    @Test fun oversizedMetadataStopsBeforeProducingASuccessfulDocument() = runBlocking {
        val oversized = ConversationBranchEntity("x".repeat(ConversationBranchExport.MAX_BYTES.toInt()), "family", createdAt = 1)
        try {
            ConversationBranchExport.writeDocument(ByteArrayOutputStream(), listOf(oversized))
            fail("Byte limit not enforced")
        } catch (_: java.io.IOException) { }
    }
}
