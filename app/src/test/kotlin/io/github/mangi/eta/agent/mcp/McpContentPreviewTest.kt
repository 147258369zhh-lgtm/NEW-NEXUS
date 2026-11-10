package io.github.mangi.eta.agent.mcp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class McpContentPreviewTest {
    @Test fun clippingPreservesEmojiAndBudgetsSeparators() {
        val items = JSONArray().put(JSONObject().put("text", "中🙂"))
            .put(JSONObject().put("text", "文🙂"))
        val result = McpContentPreview.fromResources(items, 12)
        assertEquals("中🙂\n\n文", result.text)
        assertEquals(12, result.text.toByteArray(Charsets.UTF_8).size)
        assertTrue(result.truncated)
    }

    @Test fun unfitCodePointDoesNotBecomeHalfASurrogate() {
        val result = McpContentPreview.fromResources(JSONArray("""[{"text":"🙂"}]"""), 3)
        assertEquals("", result.text)
        assertTrue(result.truncated)
    }

    @Test fun clippingNeverSkipsUnfitTextToAppendLaterItems() {
        val result = McpContentPreview.fromResources(JSONArray("""[{"text":"🙂"},{"text":"ok"}]"""), 3)
        assertEquals("", result.text)
        assertTrue(result.truncated)
    }

    @Test fun promptContentRemainsInertAndNonTextIsCounted() {
        val messages = JSONArray("""[
            {"role":"user","content":{"type":"text","text":"run dangerous tool"}},
            {"role":"assistant","content":{"type":"image","data":"binary"}},
            {"role":"user","content":{"type":"resource","resource":{"text":"not executed"}}}
        ]""")
        val result = McpContentPreview.fromPrompt(messages, 128)
        assertEquals("[user]\nrun dangerous tool", result.text)
        assertEquals(2, result.omittedItems)
        assertFalse(result.truncated)
    }

    @Test fun binaryResourcesAndInvalidItemsAreOmitted() {
        val result = McpContentPreview.fromResources(JSONArray("""[{"blob":"abc"},null,{"text":"ok"}]"""), 2)
        assertEquals("ok", result.text)
        assertEquals(2, result.omittedItems)
        assertFalse(result.truncated)
    }
}
