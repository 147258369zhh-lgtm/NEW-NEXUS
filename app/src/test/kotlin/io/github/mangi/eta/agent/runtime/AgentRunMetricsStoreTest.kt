package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.Context
import io.github.mangi.eta.fork.projects.ProjectDatabase
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AgentRunMetricsStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var db: AgentMetricsDatabase

    @Before fun setup() = runBlocking(Dispatchers.IO) {
        AgentRunMetricsStore.flush()
        AgentMetricsDatabase.closeForTests()
        context.deleteDatabase(AgentMetricsDatabase.NAME)
        context.getSharedPreferences(AgentRunMetricsStore.LEGACY_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        db = AgentMetricsDatabase.get(context)
    }

    @After fun teardown() {
        AgentRunMetricsStore.flush()
        AgentMetricsDatabase.closeForTests()
        ProjectDatabase.closeForTests()
    }

    private fun snapshot(id: String = "run", revision: Long = 1, status: String = AgentMetricStatus.RUNNING,
        time: Long = 1000) = AgentRunTelemetrySnapshot(
        runId = id, source = "agent_ui", operation = "chat", conversationId = "", roomId = "", taskId = "",
        startedAt = 100, updatedAt = time, completedAt = if (status == AgentMetricStatus.RUNNING) null else time,
        durationMs = 50, status = status, revision = revision,
        metrics = AgentRunMetrics(rounds = 1, inputTokens = 10, outputTokens = 0, inputReportedRounds = 1, outputReportedRounds = 1),
        models = listOf(AgentMetricModel(0, "fixture-provider", "fixture-model", "Fixture", AgentRunMetrics(inputTokens = 10))),
        trace = listOf(AgentTraceStep(revision, 50, if (status == AgentMetricStatus.RUNNING) "round" else status)),
        omittedTraceSteps = 0,
    )

    @Test fun aggregateTraceAndModelsReplaceAtomicallyAndRejectOldOrLateTerminalWrites() = runBlocking(Dispatchers.IO) {
        AgentRunMetricsStore.save(context, snapshot(revision = 2))
        AgentRunMetricsStore.save(context, snapshot(revision = 1).copy(metrics = AgentRunMetrics(inputTokens = 999)))
        assertEquals(10L, db.dao().record("run")?.metrics?.inputTokens)
        AgentRunMetricsStore.save(context, snapshot(revision = 3, status = AgentMetricStatus.SUCCEEDED))
        AgentRunMetricsStore.save(context, snapshot(revision = 4))
        val detail = requireNotNull(db.dao().detail("run"))
        assertEquals(AgentMetricStatus.SUCCEEDED, detail.run.status)
        assertEquals(listOf(3L), detail.trace.map { it.sequence })
        assertEquals(1, detail.models.size)
        assertEquals("fixture-model", detail.run.modelId)
        assertTrue(detail.run.metrics.usageComplete)
    }

    @Test fun legacyMigrationPreservesUnknownValuesAndDoesNotResurrectClearedHistory() = runBlocking(Dispatchers.IO) {
        val raw = JSONArray().put(JSONObject().put("run_id", "old").put("completed_at", 1000)
            .put("rounds", 2).put("tool_calls", 3).put("failed_tools", 1).put("input_tokens", 20).put("output_tokens", 0))
        context.getSharedPreferences(AgentRunMetricsStore.LEGACY_PREFS, Context.MODE_PRIVATE).edit()
            .putString(AgentRunMetricsStore.LEGACY_KEY, raw.toString()).commit()
        repeat(2) { AgentRunMetricsStore.migrateLegacy(context, db) }
        val old = requireNotNull(db.dao().record("old"))
        assertEquals(20L, old.metrics.inputTokens)
        assertNull(old.metrics.outputTokens)
        assertEquals(2, old.metrics.unresolvedTools)
        assertEquals(0, old.metrics.succeededTools)
        assertFalse(old.metrics.usageComplete)
        AgentRunMetricsStore.save(context, snapshot("active"))
        runBlocking { AgentRunMetricsStore.clear(context) }
        AgentRunMetricsStore.migrateLegacy(context, db)
        assertNull(db.dao().record("old"))
        assertNotNull(db.dao().record("active"))
        assertEquals(raw.toString(), context.getSharedPreferences(AgentRunMetricsStore.LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(AgentRunMetricsStore.LEGACY_KEY, null))
    }

    @Test fun malformedLegacyDataCannotPreventNewRecords() = runBlocking(Dispatchers.IO) {
        context.getSharedPreferences(AgentRunMetricsStore.LEGACY_PREFS, Context.MODE_PRIVATE).edit()
            .putString(AgentRunMetricsStore.LEGACY_KEY, "{corrupt").commit()
        AgentRunMetricsStore.save(context, snapshot())
        assertEquals(1, db.dao().records().size)
    }

    @Test fun pruningCascadesDetailsAndAlwaysRetainsActiveRuns() = runBlocking(Dispatchers.IO) {
        repeat(205) { AgentRunMetricsStore.save(context, snapshot("completed-$it", status = AgentMetricStatus.SUCCEEDED, time = it.toLong())) }
        repeat(10) { AgentRunMetricsStore.save(context, snapshot("active-$it", time = 0)) }
        assertEquals(210, db.dao().records().size)
        assertNull(db.dao().detail("completed-0"))
        val cursor = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM metric_trace WHERE recordId = 'completed-0'")
        cursor.use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        assertEquals(10, db.dao().records().count { it.status == AgentMetricStatus.RUNNING })
    }

    @Test fun concurrentDistinctRunsRemainIndependent() = runBlocking(Dispatchers.IO) {
        AgentRunMetricsStore.migrateLegacy(context, db)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val futures = (0..11).map { id -> executor.submit { AgentRunMetricsStore.save(context, snapshot("parallel-$id")) } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(12, db.dao().records().size)
            assertTrue(db.dao().records().all { it.metrics.inputTokens == 10L })
        } finally { executor.shutdownNow() }
    }

    @Test fun abandonedRunRecoveryAddsFrozenTerminalTraceAndPreservesModelTotals() = runBlocking(Dispatchers.IO) {
        AgentRunMetricsStore.save(context, snapshot())
        val run = requireNotNull(db.dao().record("run"))
        db.dao().putRun(run.copy(ownerSession = "dead-process"))
        db.dao().putTrace(listOf(AgentMetricTraceEntity("run", 3, 50, "round", 1, "", null)))
        db.dao().putModels(listOf(AgentMetricModelEntity("run", 0, "provider", "model", "Fixture", run.metrics)))
        db.dao().putRequests(listOf(AgentMetricRequestEntity(
            recordId = "run", requestId = "root:0", scopeId = "", requestOrder = 0,
            providerId = "provider", modelId = "model", name = "Fixture", round = 1, attempt = 1,
            startedAt = 100, startedElapsedMs = 0, completedAt = null, completedElapsedMs = null,
            status = AgentMetricStatus.RUNNING, httpCode = 200, failureCode = "",
            responseMs = 100, firstTextMs = null, totalMs = null,
            usage = AgentRequestUsage(), price = null, priceCaptured = true,
        )))
        repeat(2) { AgentRunMetricsStore.recoverInterrupted(db) }
        val recovered = requireNotNull(db.dao().detail("run"))
        assertEquals(AgentMetricStatus.INTERRUPTED, recovered.run.status)
        assertEquals(run.updatedAt, recovered.run.completedAt)
        assertEquals(run.durationMs, recovered.run.durationMs)
        assertEquals(1, recovered.trace.count { it.kind == AgentMetricStatus.INTERRUPTED })
        assertEquals(10L, recovered.models.single().metrics.inputTokens)
        assertEquals(AgentMetricStatus.INTERRUPTED, recovered.requests.single().status)
        assertEquals("process_interrupted", recovered.requests.single().failureCode)
        AgentRunMetricsStore.save(context, snapshot(revision = 9, status = AgentMetricStatus.SUCCEEDED))
        assertEquals(AgentMetricStatus.INTERRUPTED, db.dao().record("run")?.status)
    }

    @Test fun checkpointQueueCoalescesAndFlushIncludesTheTerminalSnapshot() = runBlocking(Dispatchers.IO) {
        repeat(300) { AgentRunMetricsStore.enqueue(context, snapshot(revision = it + 1L)) }
        AgentRunMetricsStore.enqueue(context, snapshot(revision = 301, status = AgentMetricStatus.CANCELLED))
        AgentRunMetricsStore.flush()
        val run = requireNotNull(db.dao().record("run"))
        assertEquals(301L, run.revision)
        assertEquals(AgentMetricStatus.CANCELLED, run.status)
        assertEquals(1, db.dao().detail("run")?.trace?.size)
    }

    @Test fun trendUsesLocalCalendarDatesAcrossDaylightSavingChangeAndKeepsUnknownUsage() = runBlocking(Dispatchers.IO) {
        AgentRunMetricsStore.save(context, snapshot("before-midnight").copy(
            startedAt = java.time.Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()))
        AgentRunMetricsStore.save(context, snapshot("after-midnight").copy(
            startedAt = java.time.Instant.parse("2026-11-01T08:30:00Z").toEpochMilli(), metrics = AgentRunMetrics(rounds = 1)))
        val trend = AgentMetricsTrend.lastWeek(db.dao().records(),
            java.time.Instant.parse("2026-11-01T20:00:00Z").toEpochMilli(), java.time.ZoneId.of("America/Los_Angeles"))
        assertEquals(7, trend.size)
        assertEquals(java.time.LocalDate.of(2026, 10, 26), trend.first().date)
        assertEquals(1, trend[5].runs)
        assertEquals(10L, trend[5].inputTokens)
        assertEquals(1, trend[6].runs)
        assertNull(trend[6].inputTokens)
        assertEquals(1, trend[6].incompleteUsage)
        assertEquals(0, trend.first().runs)
        assertNull(trend.first().inputTokens)
    }

    @Test fun rootCheckpointsPreserveChildrenAndClearOnlyCompletedHistoryNotPriceConfiguration() = runBlocking(Dispatchers.IO) {
        val request = AgentRuntimeWire.RunRequest(runId = "child-root", prompt = "prompt", images = emptyList(),
            config = io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "m", systemPrompt = "fixture"))
        val price = AgentTokenPrice.create("USD", "2", "8")
        AgentTokenPriceStore.save(context, "p", "m", price)
        val recorder = AgentRunTelemetry(request, priceResolver = { _, _ -> price })
        AgentRunMetricsStore.save(context, recorder.snapshot())
        val child = requireNotNull(recorder.scope.child("subagent", request.config))
        child.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 20, outputTokens = 2, billingBasis = AgentBillingTokens.OPENAI)))
        AgentRunMetricsStore.save(context, recorder.snapshot())
        val childId = db.dao().children("child-root").single().scopeId
        val root = requireNotNull(db.dao().record("child-root"))
        db.dao().putRun(root.copy(revision = root.revision + 1)) // Updating a parent must not cascade-delete child rows.
        assertEquals(childId, db.dao().children("child-root").single().scopeId)
        child.finish(true)
        AgentRunMetricsStore.save(context, recorder.finish(true))
        val detail = requireNotNull(db.dao().detail("child-root"))
        assertEquals(AgentMetricStatus.SUCCEEDED, detail.children.single().status)
        assertEquals(20L, detail.children.single().metrics.inputTokens)
        assertEquals(price.revision, detail.children.single().price?.revision)
        val billable = db.dao().modelsFlow().first()
        assertEquals(2, billable.size)
        assertTrue(billable.any { it.metrics.inputTokens == 20L })
        AgentRunMetricsStore.clear(context)
        assertTrue(db.dao().children("child-root").isEmpty())
        assertEquals(price, db.dao().price("p", "m")?.price)
    }

    @Test fun restartFreezesUnfinishedChildrenWithoutRecountingOrRerunningThem() = runBlocking(Dispatchers.IO) {
        val request = AgentRuntimeWire.RunRequest(runId = "abandoned", prompt = "prompt", images = emptyList(),
            config = io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "m", systemPrompt = "fixture"))
        val recorder = AgentRunTelemetry(request)
        val scope = requireNotNull(recorder.scope.child("subagent", request.config))
        scope.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 20)))
        AgentRunMetricsStore.save(context, recorder.snapshot())
        val root = requireNotNull(db.dao().record("abandoned"))
        db.dao().putRun(root.copy(ownerSession = "dead-process"))
        val before = db.dao().children("abandoned").single()
        repeat(2) { AgentRunMetricsStore.recoverInterrupted(db) }
        val child = db.dao().children("abandoned").single()
        assertEquals(AgentMetricStatus.INTERRUPTED, child.status)
        assertEquals(before.durationMs, child.durationMs)
        assertEquals(before.metrics, child.metrics)
        assertEquals(before.revision + 1, child.revision)
        scope.finish(true)
        AgentRunMetricsStore.save(context, recorder.finish(true))
        assertEquals(child, db.dao().children("abandoned").single())
    }

    @Test fun requestLedgerPersistsAndUpdatesTheSameHttpAttemptWithoutDuplicatingIt() = runBlocking(Dispatchers.IO) {
        val request = AgentRuntimeWire.RunRequest(runId = "request-run", prompt = "prompt", images = emptyList(),
            config = io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(
                baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "m", providerId = "p", systemPrompt = "fixture"))
        val recorder = AgentRunTelemetry(request)
        recorder.accept(AgentEvent.ProviderRequestStarted(1))
        AgentRunMetricsStore.save(context, recorder.snapshot())
        assertEquals(AgentMetricStatus.RUNNING, db.dao().requests("request-run").single().status)

        recorder.accept(AgentEvent.ProviderResponseStarted(1, 200))
        recorder.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 20, outputTokens = 2,
            billingBasis = AgentBillingTokens.OPENAI)))
        recorder.accept(AgentEvent.AssistantReceived(1, 1, "", emptyList()))
        AgentRunMetricsStore.save(context, recorder.finish(true))

        val stored = db.dao().requests("request-run").single()
        assertEquals(AgentMetricStatus.SUCCEEDED, stored.status)
        assertEquals(200, stored.httpCode)
        assertEquals(20L, stored.usage.inputTokens)
        assertEquals(2L, stored.usage.outputTokens)
        assertNotNull(stored.totalMs)
    }

    @Test fun detailFlowIncludesThePersistedRequestLedger() = runBlocking(Dispatchers.IO) {
        val request = AgentRuntimeWire.RunRequest(runId = "request-flow", prompt = "prompt", images = emptyList(),
            config = io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig(
                baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "m", providerId = "p", systemPrompt = "fixture"))
        val recorder = AgentRunTelemetry(request)
        recorder.accept(AgentEvent.ProviderRequestStarted(1))
        recorder.accept(AgentEvent.ProviderResponseStarted(1, 200))
        recorder.accept(AgentEvent.UsageReceived(1, AgentTokenUsage(inputTokens = 20, outputTokens = 2,
            billingBasis = AgentBillingTokens.OPENAI)))
        recorder.accept(AgentEvent.AssistantReceived(1, 1, "done", emptyList()))
        AgentRunMetricsStore.save(context, recorder.finish(true))

        val detail = requireNotNull(AgentRunMetricsStore.detail(context, "request-flow").first())
        val stored = detail.requests.single()
        assertEquals("root:0", stored.requestId)
        assertEquals(200, stored.httpCode)
        assertEquals(20L, stored.usage.inputTokens)
        assertEquals(2L, stored.usage.outputTokens)
    }
}
