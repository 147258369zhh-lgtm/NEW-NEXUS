package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderParameterDiagnosticsTest {
    @Test
    fun invalidToolSchemaReportsSafePathAndHintWithoutEchoingMessage() {
        val details = ProviderParameterDiagnostics.describe(
            JSONObject()
                .put("type", "invalid_request_error")
                .put("param", "tools[0].function.parameters")
                .put("message", "Invalid schema for private-user-prompt"),
        )

        assertTrue(details.orEmpty().contains("invalid_request_error"))
        assertTrue(details.orEmpty().contains("tools[0].function.parameters"))
        assertTrue(details.orEmpty().contains("工具定义不受支持"))
        assertFalse(details.orEmpty().contains("private-user-prompt"))
    }
}
