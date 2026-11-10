package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AgentMetricsExportTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun setup() = runBlocking(Dispatchers.IO) {
        AgentRunMetricsStore.flush()
        AgentMetricsDatabase.closeForTests()
        context.deleteDatabase(AgentMetricsDatabase.NAME)
        val record = AgentRunMetricsRecord(recordId = "run", runId = "run", ownerSession = "PRIVATE_SESSION",
            source = "meeting", operation = "chat", conversationId = "", roomId = "room", taskId = "", projectId = "",
            startedAt = 100, updatedAt = 500, completedAt = 500, durationMs = 400,
            status = AgentMetricStatus.SUCCEEDED, revision = 2, omittedTraceSteps = 0,
            metrics = AgentRunMetrics(rounds = 1, inputTokens = 0, outputTokens = null, inputReportedRounds = 1))
        val db = AgentMetricsDatabase.get(context)
        db.dao().putRun(record)
        db.dao().putTrace(listOf(AgentMetricTraceEntity("run", 1, 400, "succeeded", null, "", true)))
    }

    @After fun teardown() { AgentRunMetricsStore.flush(); AgentMetricsDatabase.closeForTests() }
    private fun assertClean() = assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("eta-metrics-export-") })

    @Test fun selectedRecordsExportNullAndZeroDistinctlyAndReportRecordsRemovedSinceSelection() = runBlocking {
        val target = File(context.cacheDir, "metric-test.json")
        val result = AgentMetricsExport.export(context, Uri.fromFile(target), listOf("run", "missing", "run"))
        assertEquals(1, result.exportedRuns)
        assertEquals(1, result.missingRuns)
        val raw = target.readText()
        val json = JSONObject(raw)
        assertEquals("eta_run_diagnostics", json.getString("type"))
        val run = json.getJSONArray("runs").getJSONObject(0)
        assertEquals("room", run.getString("room_id"))
        assertEquals(0L, run.getJSONObject("usage").getLong("input_tokens"))
        assertTrue(run.getJSONObject("usage").isNull("output_tokens"))
        assertFalse(run.getJSONObject("usage").getBoolean("usage_complete"))
        assertFalse(raw.contains("PRIVATE_SESSION"))
        assertFalse(run.has("ownerSession"))
        assertEquals("succeeded", run.getJSONArray("trace").getJSONObject(0).getString("kind"))
        assertClean()
    }

    @Test fun missingSelectionNeverTruncatesAnExistingFile() = runBlocking {
        val target = File(context.cacheDir, "missing-metric-test.json").apply { writeText("original") }
        val result = runCatching { AgentMetricsExport.export(context, Uri.fromFile(target), listOf("missing")) }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals("original", target.readText())
        assertClean()
    }

    @Test fun destinationCloseFailureDoesNotReportSuccessAndCleansStaging() = runBlocking {
        val uri = Uri.parse("content://metrics.test/close-failure")
        shadowOf(context.contentResolver).registerOutputStream(uri, object : ByteArrayOutputStream() {
            override fun close() { throw IOException("close failed") }
        })
        assertTrue(runCatching { AgentMetricsExport.export(context, uri, listOf("run")) }.exceptionOrNull() is IOException)
        assertClean()
    }

    @Test fun cancellationDuringCopyDoesNotReportSuccessOrLeaveStaging() = runBlocking {
        supervisorScope {
            val uri = Uri.parse("content://metrics.test/cancel")
            lateinit var operation: Deferred<AgentMetricsExportResult>
            shadowOf(context.contentResolver).registerOutputStream(uri, object : ByteArrayOutputStream() {
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    super.write(bytes, offset, length)
                    operation.cancel()
                }
            })
            operation = async(start = CoroutineStart.LAZY) { AgentMetricsExport.export(context, uri, listOf("run")) }
            operation.start()
            assertTrue(runCatching { operation.await() }.exceptionOrNull() is CancellationException)
            operation.join()
        }
        assertClean()
    }

    @Test fun selectionLimitIsEnforcedBeforeOpeningDestination() = runBlocking {
        val target = File(context.cacheDir, "metrics-limit.json").apply { writeText("original") }
        val result = runCatching { AgentMetricsExport.export(context, Uri.fromFile(target), (0..256).map { "run-$it" }) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertEquals("original", target.readText())
        assertClean()
    }

    @Test fun exportKeepsExactDecimalSnapshotsUnknownPricesAndPartialCoverage() = runBlocking(Dispatchers.IO) {
        val db = AgentMetricsDatabase.get(context)
        val usage = AgentRunMetrics(rounds = 1, uncachedInputTokens = 0, cacheReadTokens = 0, cacheWriteTokens = 0,
            billableOutputTokens = 1, billingReportedRounds = 1)
        val price = AgentTokenPrice.create("USD", "0", "0.00000001", now = 100)
        db.dao().putModels(listOf(AgentMetricModelEntity("run", 0, "p", "m", "M", usage, price, true),
            AgentMetricModelEntity("run", 1, "p", "unknown", "Unknown", AgentRunMetrics(rounds = 1), null, true)))
        db.dao().putChildren(listOf(AgentMetricChildEntity(recordId = "run", scopeId = "child", parentScopeId = "", index = 0,
            kind = "compaction", parentRound = null, toolCallId = "", startedAt = 100, completedAt = 200, durationMs = 100,
            status = AgentMetricStatus.SUCCEEDED, revision = 1, providerId = "p", modelId = "m", name = "M",
            metrics = usage, price = price, priceCaptured = true)))
        db.dao().putRequests(listOf(AgentMetricRequestEntity(
            recordId = "run", requestId = "root:0", scopeId = "", requestOrder = 0,
            providerId = "p", modelId = "m", name = "M", round = 1, attempt = 1,
            startedAt = 100, startedElapsedMs = 0, completedAt = 500, completedElapsedMs = 400,
            status = AgentMetricStatus.SUCCEEDED, httpCode = 200, failureCode = "",
            responseMs = 100, firstTextMs = 180, totalMs = 400,
            usage = AgentRequestUsage(inputTokens = 1_200, outputTokens = 100, cachedTokens = 200,
                uncachedInputTokens = 1_000, cacheReadTokens = 200, cacheWriteTokens = 0, billableOutputTokens = 100),
            price = price, priceCaptured = true,
        )))
        val target = File(context.cacheDir, "cost-metric.json")
        AgentMetricsExport.export(context, Uri.fromFile(target), listOf("run"))
        val json = JSONObject(target.readText())
        assertEquals(3, json.getInt("version"))
        val run = json.getJSONArray("runs").getJSONObject(0)
        val models = run.getJSONArray("models")
        assertEquals("0.00000000000001", models.getJSONObject(0).getJSONObject("token_cost").getString("known_amount"))
        assertEquals(price.revision, models.getJSONObject(0).getJSONObject("price").getString("revision"))
        assertTrue(models.getJSONObject(0).getJSONObject("price").isNull("cache_read"))
        assertTrue(models.getJSONObject(1).isNull("price"))
        assertFalse(models.getJSONObject(1).getJSONObject("token_cost").getBoolean("complete"))
        assertEquals(2, run.getJSONObject("token_cost").getInt("complete_model_attempts"))
        assertEquals(3, run.getJSONObject("token_cost").getInt("model_attempts"))
        assertEquals("0.00000000000002", run.getJSONObject("token_cost").getJSONArray("currencies").getJSONObject(0).getString("known_amount"))
        assertEquals("compaction", run.getJSONArray("children").getJSONObject(0).getString("kind"))
        assertEquals(0L, models.getJSONObject(0).getJSONObject("usage").getLong("cache_read_tokens"))
        val request = run.getJSONArray("requests").getJSONObject(0)
        assertEquals(200, request.getInt("http_code"))
        assertEquals(180L, request.getLong("first_text_ms"))
        assertEquals(200L, request.getJSONObject("usage").getLong("cache_read_tokens"))
        assertFalse(request.getJSONObject("token_cost").getBoolean("complete"))
        assertClean()
    }
}
