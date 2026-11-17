package io.github.mangi.eta.agent.capability

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class A2AToolCallIdentityTest {
    @Test
    fun `canonical digest ignores object key order`() {
        assertEquals(
            A2AToolCallIdentity.canonicalArgumentsDigest("""{"b":2,"a":1}"""),
            A2AToolCallIdentity.canonicalArgumentsDigest("""{"a":1,"b":2}"""),
        )
        assertEquals(
            A2AToolCallIdentity.canonicalArgumentsDigest("""{"b":2,"a":1}"""),
            A2AToolCallIdentity.canonicalArgumentsDigest(JSONObject("""{"a":1,"b":2}""")),
        )
    }

    @Test
    fun `digest changes when a value changes and blank arguments mean an empty object`() {
        assertNotEquals(
            A2AToolCallIdentity.canonicalArgumentsDigest("""{"path":"a"}"""),
            A2AToolCallIdentity.canonicalArgumentsDigest("""{"path":"b"}"""),
        )
        assertEquals(
            A2AToolCallIdentity.canonicalArgumentsDigest("{}"),
            A2AToolCallIdentity.canonicalArgumentsDigest(""),
        )
        val whitespacePadded = "{" + " ".repeat(40_000) + "\"path\":\"a\"}"
        assertEquals(
            A2AToolCallIdentity.canonicalArgumentsDigest(JSONObject("""{"path":"a"}""")),
            A2AToolCallIdentity.canonicalArgumentsDigest(whitespacePadded),
        )
    }

    @Test
    fun `oversized or malformed arguments have no digest`() {
        assertEquals("", A2AToolCallIdentity.canonicalArgumentsDigest("{not json"))
        assertEquals("", A2AToolCallIdentity.canonicalArgumentsDigest("x".repeat(40_000)))
    }

    @Test
    fun `settled key is only built from a complete identity`() {
        val digest = "c".repeat(64)
        assertEquals("write_file|REVERSIBLE_WRITE|$digest", A2AToolCallIdentity.settledKey("write_file", "REVERSIBLE_WRITE", digest))
        assertEquals("", A2AToolCallIdentity.settledKey("", "REVERSIBLE_WRITE", digest))
        assertEquals("", A2AToolCallIdentity.settledKey("write_file", "", digest))
        assertEquals("", A2AToolCallIdentity.settledKey("write_file", "REVERSIBLE_WRITE", "short"))
    }
}
