package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.ContentValues
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
class AgentMetricsMigrationTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        AgentRunMetricsStore.flush()
        AgentMetricsDatabase.closeForTests()
        context.deleteDatabase(AgentMetricsDatabase.NAME)
    }
    @After fun teardown() { AgentRunMetricsStore.flush(); AgentMetricsDatabase.closeForTests() }

    @Test fun opensAnActualV1DatabaseAndPreservesRunsModelsTraceAndUnknownBilling() = runBlocking(Dispatchers.IO) {
        createV1()
        val db = AgentMetricsDatabase.get(context)
        val detail = requireNotNull(db.dao().detail("old")) // Room validates migrated tables, keys and indices.
        assertEquals(3, db.openHelper.readableDatabase.version)
        assertEquals(10L, detail.run.metrics.inputTokens)
        assertEquals(2L, detail.models.single().metrics.outputTokens)
        assertEquals("read_file", detail.trace.single().label)
        assertEquals("done", db.dao().meta("fixture"))
        assertNull(detail.run.metrics.uncachedInputTokens)
        assertNull(detail.models.single().price)
        assertFalse(detail.models.single().priceCaptured)
        assertEquals(0, detail.run.metrics.billingReportedRounds)
        assertTrue(detail.requests.isEmpty())
        assertEquals(10L, db.dao().modelsFlow().first().single().metrics.inputTokens)
        val price = AgentTokenPrice.create("USD", "2", "8", "0", now = 200)
        AgentTokenPriceStore.save(context, "p", "m", price)
        assertEquals(price, db.dao().price("p", "m")?.price)
        db.dao().clearCompleted()
        assertNull(db.dao().detail("old"))
        assertEquals(price, db.dao().price("p", "m")?.price)
        AgentMetricsDatabase.closeForTests()
        assertEquals(price, AgentTokenPriceStore.resolve(context, "p", "m"))
    }

    private fun createV1() {
        val nonnullMetrics = listOf("rounds", "toolCalls", "succeededTools", "failedTools", "unresolvedTools",
            "inputReportedRounds", "outputReportedRounds", "imageBytes", "requestAttempts", "retries", "completedCompactions")
        val nullableMetrics = listOf("inputTokens", "outputTokens", "reasoningTokens", "cachedTokens", "contextTokens")
        val usageColumns = (nonnullMetrics.map { "`usage_$it` INTEGER NOT NULL" } + nullableMetrics.map { "`usage_$it` INTEGER" }).joinToString(",")
        val runStrings = listOf("recordId", "runId", "ownerSession", "source", "operation", "conversationId", "roomId", "taskId", "projectId",
            "status", "providerId", "modelId", "modelName")
        val runLongs = listOf("startedAt", "updatedAt", "durationMs", "revision", "omittedTraceSteps")
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(AgentMetricsDatabase.NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE metric_runs (${runStrings.joinToString(",") { "`$it` TEXT NOT NULL" }}," +
                        "${runLongs.joinToString(",") { "`$it` INTEGER NOT NULL" }},completedAt INTEGER,$usageColumns,PRIMARY KEY(recordId))")
                    listOf("updatedAt", "source", "status").forEach { db.execSQL("CREATE INDEX index_metric_runs_$it ON metric_runs(`$it`)") }
                    db.execSQL("CREATE TABLE metric_models (recordId TEXT NOT NULL, `index` INTEGER NOT NULL, providerId TEXT NOT NULL," +
                        "modelId TEXT NOT NULL, name TEXT NOT NULL,$usageColumns, PRIMARY KEY(recordId,`index`)," +
                        "FOREIGN KEY(recordId) REFERENCES metric_runs(recordId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE TABLE metric_trace (recordId TEXT NOT NULL, sequence INTEGER NOT NULL, elapsedMs INTEGER NOT NULL," +
                        "kind TEXT NOT NULL, round INTEGER,label TEXT NOT NULL,success INTEGER,PRIMARY KEY(recordId,sequence)," +
                        "FOREIGN KEY(recordId) REFERENCES metric_runs(recordId) ON UPDATE NO ACTION ON DELETE CASCADE)")
                    db.execSQL("CREATE TABLE metric_meta (`key` TEXT NOT NULL,value TEXT NOT NULL,PRIMARY KEY(`key`))")
                    val run = ContentValues().apply {
                        runStrings.forEach { put(it, "") }; runLongs.forEach { put(it, 100L) }
                        nonnullMetrics.forEach { put("usage_$it", 0) }
                        put("recordId", "old"); put("runId", "old"); put("status", AgentMetricStatus.SUCCEEDED)
                        put("providerId", "p"); put("modelId", "m"); put("modelName", "Fixture")
                        put("usage_rounds", 1); put("usage_inputTokens", 10L); put("usage_outputTokens", 2L)
                        put("usage_inputReportedRounds", 1); put("usage_outputReportedRounds", 1)
                    }
                    db.insert("metric_runs", 0, run)
                    val model = ContentValues().apply {
                        nonnullMetrics.forEach { put("usage_$it", 0) }
                        put("recordId", "old"); put("`index`", 0); put("providerId", "p"); put("modelId", "m"); put("name", "Fixture")
                        put("usage_rounds", 1); put("usage_inputTokens", 10L); put("usage_outputTokens", 2L)
                    }
                    db.insert("metric_models", 0, model)
                    db.execSQL("INSERT INTO metric_trace VALUES ('old',1,10,'tool_finish',1,'read_file',1)")
                    db.execSQL("INSERT INTO metric_meta VALUES ('fixture','done')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { it.writableDatabase }
    }
}
