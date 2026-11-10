package io.github.mangi.eta.agent.control

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaAppControlAuditStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun persistsAuditEntriesAndRedactsCredentials() {
        val store = EtaAppControlAuditStore(context)
        store.append(
            operation = "execute",
            capabilityId = "settings.current_model",
            parameters = JSONObject()
                .put("apiKey", "secret-value")
                .put("nested", JSONObject().put("accessToken", "token-value"))
                .put("modelId", "model-a"),
            success = true,
            durationMs = 12,
        )
        store.append(
            operation = "get",
            capabilityId = "state.runtime",
            parameters = JSONObject(),
            success = false,
            errorCode = "CAPABILITY_FAILED",
            durationMs = 4,
        )

        val entries = store.list(1)
        assertEquals(1, entries.size)
        assertEquals("state.runtime", entries.single().getString("capabilityId"))

        val all = store.list(10)
        val first = all.first()
        assertEquals("[REDACTED]", first.getJSONObject("parameters").getString("apiKey"))
        assertEquals(
            "[REDACTED]",
            first.getJSONObject("parameters").getJSONObject("nested").getString("accessToken"),
        )
        assertEquals("model-a", first.getJSONObject("parameters").getString("modelId"))
        val summary = store.summary()
        assertEquals(2, summary.getInt("total"))
        assertEquals(1, summary.getInt("failed"))
        assertTrue(summary.getLong("lastTimestamp") > 0L)
        assertFalse(all.any { it.toString().contains("token-value") })
    }
}
