package io.github.mangi.eta.agent.control

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EtaControlSchemaTest {
    private val schema = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put("enabled", JSONObject().put("type", "boolean"))
                .put("mode", JSONObject().put("type", "string").put("enum", JSONArray(listOf("safe", "full"))))
                .put("modelId", JSONObject().put("type", JSONArray().put("string").put("null")))
                .put(
                    "tools",
                    JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")),
                ),
        )
        .put("required", JSONArray(listOf("enabled", "mode")))
        .put("additionalProperties", false)

    @Test
    fun acceptsValidParameters() {
        EtaControlSchema.validate(
            schema,
            JSONObject().put("enabled", true).put("mode", "safe").put("modelId", JSONObject.NULL),
        )
    }

    @Test
    fun rejectsMissingOrUnexpectedParameters() {
        val missing = assertThrows(EtaControlException::class.java) {
            EtaControlSchema.validate(schema, JSONObject().put("enabled", true))
        }
        assertEquals("INVALID_PARAMETERS", missing.code)

        val unexpected = assertThrows(EtaControlException::class.java) {
            EtaControlSchema.validate(
                schema,
                JSONObject().put("enabled", true).put("mode", "safe").put("extra", true),
            )
        }
        assertEquals("INVALID_PARAMETERS", unexpected.code)
    }

    @Test
    fun rejectsInvalidTypeAndEnumValue() {
        assertThrows(EtaControlException::class.java) {
            EtaControlSchema.validate(schema, JSONObject().put("enabled", "yes").put("mode", "safe"))
        }
        assertThrows(EtaControlException::class.java) {
            EtaControlSchema.validate(schema, JSONObject().put("enabled", true).put("mode", "unknown"))
        }
        assertThrows(EtaControlException::class.java) {
            EtaControlSchema.validate(
                schema,
                JSONObject().put("enabled", true).put("mode", "safe").put("tools", JSONArray(listOf(1))),
            )
        }
    }
}
