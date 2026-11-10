package io.github.mangi.eta.agent.runtime

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "metric_runs", indices = [Index("updatedAt"), Index("source"), Index("status")])
internal data class AgentRunMetricsRecord(
    @PrimaryKey val recordId: String,
    val runId: String, val ownerSession: String, val source: String, val operation: String,
    val conversationId: String, val roomId: String, val taskId: String, val projectId: String,
    val startedAt: Long, val updatedAt: Long, val completedAt: Long?, val durationMs: Long,
    val status: String, val revision: Long, val omittedTraceSteps: Long,
    @Embedded(prefix = "usage_") val metrics: AgentRunMetrics,
    val providerId: String = "", val modelId: String = "", val modelName: String = "",
)

@Entity(tableName = "metric_trace", primaryKeys = ["recordId", "sequence"], foreignKeys = [
    ForeignKey(entity = AgentRunMetricsRecord::class, parentColumns = ["recordId"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE),
])
internal data class AgentMetricTraceEntity(
    val recordId: String, val sequence: Long, val elapsedMs: Long, val kind: String,
    val round: Int?, val label: String, val success: Boolean?,
)

@Entity(tableName = "metric_models", primaryKeys = ["recordId", "index"], foreignKeys = [
    ForeignKey(entity = AgentRunMetricsRecord::class, parentColumns = ["recordId"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE),
])
internal data class AgentMetricModelEntity(
    val recordId: String, val index: Int, val providerId: String, val modelId: String, val name: String,
    @Embedded(prefix = "usage_") val metrics: AgentRunMetrics,
    @Embedded(prefix = "price_") val price: AgentTokenPrice? = null,
    @ColumnInfo(defaultValue = "0") val priceCaptured: Boolean = false,
)

@Entity(tableName = "metric_prices", primaryKeys = ["providerId", "modelId"])
internal data class AgentTokenPriceEntity(
    val providerId: String, val modelId: String,
    @Embedded(prefix = "price_") val price: AgentTokenPrice,
)

@Entity(tableName = "metric_children", primaryKeys = ["recordId", "scopeId"], foreignKeys = [
    ForeignKey(entity = AgentRunMetricsRecord::class, parentColumns = ["recordId"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE),
])
internal data class AgentMetricChildEntity(
    val recordId: String, val scopeId: String, val parentScopeId: String, val index: Int, val kind: String,
    val parentRound: Int?, val toolCallId: String, val startedAt: Long, val completedAt: Long?,
    val durationMs: Long, val status: String, val revision: Long,
    val providerId: String, val modelId: String, val name: String,
    @Embedded(prefix = "usage_") val metrics: AgentRunMetrics,
    @Embedded(prefix = "price_") val price: AgentTokenPrice?,
    val priceCaptured: Boolean,
) {
    fun asModel() = AgentMetricModelEntity(recordId, index, providerId, modelId, name, metrics, price, priceCaptured)
}

@Entity(tableName = "metric_requests", primaryKeys = ["recordId", "requestId"], indices = [Index("recordId"), Index("scopeId")], foreignKeys = [
    ForeignKey(entity = AgentRunMetricsRecord::class, parentColumns = ["recordId"], childColumns = ["recordId"], onDelete = ForeignKey.CASCADE),
])
internal data class AgentMetricRequestEntity(
    val recordId: String,
    val requestId: String,
    val scopeId: String,
    val requestOrder: Long,
    val providerId: String,
    val modelId: String,
    val name: String,
    val round: Int?,
    val attempt: Int,
    val startedAt: Long,
    val startedElapsedMs: Long,
    val completedAt: Long?,
    val completedElapsedMs: Long?,
    val status: String,
    val httpCode: Int?,
    val failureCode: String,
    val responseMs: Long?,
    val firstTextMs: Long?,
    val totalMs: Long?,
    @Embedded(prefix = "usage_") val usage: AgentRequestUsage,
    @Embedded(prefix = "price_") val price: AgentTokenPrice?,
    val priceCaptured: Boolean,
)

@Entity(tableName = "metric_meta")
internal data class AgentMetricMetaEntity(@PrimaryKey val key: String, val value: String)

internal data class AgentMetricDetail(
    @Embedded val run: AgentRunMetricsRecord,
    @Relation(parentColumn = "recordId", entityColumn = "recordId") val trace: List<AgentMetricTraceEntity>,
    @Relation(parentColumn = "recordId", entityColumn = "recordId") val models: List<AgentMetricModelEntity>,
    @Relation(parentColumn = "recordId", entityColumn = "recordId") val children: List<AgentMetricChildEntity> = emptyList(),
    @Relation(parentColumn = "recordId", entityColumn = "recordId") val requests: List<AgentMetricRequestEntity> = emptyList(),
)

internal data class AgentMetricOverview(
    @Embedded val run: AgentRunMetricsRecord,
    @Relation(parentColumn = "recordId", entityColumn = "recordId") val models: List<AgentMetricModelEntity>,
    @Relation(parentColumn = "recordId", entityColumn = "recordId") val children: List<AgentMetricChildEntity>,
)

private const val BILLABLE_COLUMNS = "recordId, `index`, providerId, modelId, name, " +
    "usage_rounds, usage_toolCalls, usage_succeededTools, usage_failedTools, usage_unresolvedTools, " +
    "usage_inputTokens, usage_outputTokens, usage_reasoningTokens, usage_cachedTokens, usage_contextTokens, " +
    "usage_inputReportedRounds, usage_outputReportedRounds, usage_imageBytes, usage_requestAttempts, usage_retries, " +
    "usage_completedCompactions, usage_uncachedInputTokens, usage_cacheReadTokens, usage_cacheWriteTokens, " +
    "usage_billableOutputTokens, usage_billingReportedRounds, price_revision, price_currency, price_input, price_output, " +
    "price_cacheRead, price_cacheWrite, price_capturedAt, priceCaptured"

@Dao
internal interface AgentMetricsDao {
    @Transaction @Query("SELECT * FROM metric_runs ORDER BY updatedAt DESC, recordId ASC") fun overviewFlow(): Flow<List<AgentMetricOverview>>
    @Query("SELECT * FROM metric_runs ORDER BY updatedAt DESC, recordId ASC") fun recordsFlow(): Flow<List<AgentRunMetricsRecord>>
    @Query("SELECT * FROM metric_runs ORDER BY updatedAt DESC, recordId ASC") fun records(): List<AgentRunMetricsRecord>
    @Query("SELECT * FROM metric_runs WHERE recordId = :id") fun record(id: String): AgentRunMetricsRecord?
    @Query("SELECT $BILLABLE_COLUMNS FROM metric_models UNION ALL SELECT $BILLABLE_COLUMNS FROM metric_children")
    fun modelsFlow(): Flow<List<AgentMetricModelEntity>>
    @Query("SELECT * FROM metric_prices ORDER BY providerId, modelId") fun pricesFlow(): Flow<List<AgentTokenPriceEntity>>
    @Query("SELECT * FROM metric_prices WHERE providerId = :provider AND modelId = :model") fun price(provider: String, model: String): AgentTokenPriceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putPrice(price: AgentTokenPriceEntity)
    @Query("DELETE FROM metric_prices WHERE providerId = :provider AND modelId = :model") fun deletePrice(provider: String, model: String)
    @Transaction @Query("SELECT * FROM metric_runs WHERE recordId = :id") fun detailFlow(id: String): Flow<AgentMetricDetail?>
    @Transaction @Query("SELECT * FROM metric_runs WHERE recordId = :id") fun detail(id: String): AgentMetricDetail?
    @Upsert fun putRun(run: AgentRunMetricsRecord)
    @Insert fun putTrace(trace: List<AgentMetricTraceEntity>)
    @Upsert fun putModels(models: List<AgentMetricModelEntity>)
    @Upsert fun putChildren(children: List<AgentMetricChildEntity>)
    @Upsert fun putRequests(requests: List<AgentMetricRequestEntity>)
    @Query("SELECT * FROM metric_children WHERE recordId = :id") fun children(id: String): List<AgentMetricChildEntity>
    @Query("SELECT * FROM metric_requests WHERE recordId = :id ORDER BY requestOrder, requestId") fun requests(id: String): List<AgentMetricRequestEntity>
    @Query("SELECT * FROM metric_requests WHERE recordId = :id ORDER BY requestOrder, requestId") fun requestsFlow(id: String): Flow<List<AgentMetricRequestEntity>>
    @Query("DELETE FROM metric_trace WHERE recordId = :id") fun clearTrace(id: String)
    @Query("DELETE FROM metric_models WHERE recordId = :id") fun clearModels(id: String)
    @Query("SELECT * FROM metric_runs WHERE status = 'running' AND ownerSession != :owner") fun abandonedRuns(owner: String): List<AgentRunMetricsRecord>
    @Query("SELECT value FROM metric_meta WHERE `key` = :key") fun meta(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putMeta(meta: AgentMetricMetaEntity)
    @Query("DELETE FROM metric_runs WHERE status != 'running'") fun clearCompleted()
    @Query("DELETE FROM metric_runs WHERE status != 'running' AND recordId NOT IN (SELECT recordId FROM metric_runs WHERE status != 'running' ORDER BY updatedAt DESC, recordId ASC LIMIT :limit)") fun prune(limit: Int)
}

@Database(entities = [AgentRunMetricsRecord::class, AgentMetricTraceEntity::class, AgentMetricModelEntity::class, AgentMetricMetaEntity::class, AgentTokenPriceEntity::class, AgentMetricChildEntity::class, AgentMetricRequestEntity::class], version = 3, exportSchema = false)
internal abstract class AgentMetricsDatabase : RoomDatabase() {
    abstract fun dao(): AgentMetricsDao

    companion object {
        const val NAME = "eta_run_metrics.db"
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("metric_runs", "metric_models").forEach { table ->
                    listOf("uncachedInputTokens", "cacheReadTokens", "cacheWriteTokens", "billableOutputTokens").forEach { column ->
                        db.execSQL("ALTER TABLE `$table` ADD COLUMN `usage_$column` INTEGER")
                    }
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `usage_billingReportedRounds` INTEGER NOT NULL DEFAULT 0")
                }
                listOf("revision", "currency", "input", "output", "cacheRead", "cacheWrite").forEach { column ->
                    db.execSQL("ALTER TABLE metric_models ADD COLUMN `price_$column` TEXT")
                }
                db.execSQL("ALTER TABLE metric_models ADD COLUMN price_capturedAt INTEGER")
                db.execSQL("ALTER TABLE metric_models ADD COLUMN priceCaptured INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""CREATE TABLE IF NOT EXISTS metric_prices (
                    providerId TEXT NOT NULL, modelId TEXT NOT NULL, price_revision TEXT NOT NULL,
                    price_currency TEXT NOT NULL, price_input TEXT NOT NULL, price_output TEXT NOT NULL,
                    price_cacheRead TEXT, price_cacheWrite TEXT, price_capturedAt INTEGER NOT NULL,
                    PRIMARY KEY(providerId, modelId))""")
                val integers = listOf("rounds", "toolCalls", "succeededTools", "failedTools", "unresolvedTools",
                    "inputReportedRounds", "outputReportedRounds", "imageBytes", "requestAttempts", "retries", "completedCompactions")
                val nullable = listOf("inputTokens", "outputTokens", "reasoningTokens", "cachedTokens", "contextTokens",
                    "uncachedInputTokens", "cacheReadTokens", "cacheWriteTokens", "billableOutputTokens")
                val usage = (integers.map { "`usage_$it` INTEGER NOT NULL" } + nullable.map { "`usage_$it` INTEGER" } +
                    "usage_billingReportedRounds INTEGER NOT NULL DEFAULT 0").joinToString(",")
                db.execSQL("""CREATE TABLE IF NOT EXISTS metric_children (
                    recordId TEXT NOT NULL, scopeId TEXT NOT NULL, parentScopeId TEXT NOT NULL, `index` INTEGER NOT NULL,
                    kind TEXT NOT NULL, parentRound INTEGER, toolCallId TEXT NOT NULL, startedAt INTEGER NOT NULL, completedAt INTEGER,
                    durationMs INTEGER NOT NULL, status TEXT NOT NULL, revision INTEGER NOT NULL,
                    providerId TEXT NOT NULL, modelId TEXT NOT NULL, name TEXT NOT NULL, $usage,
                    price_revision TEXT, price_currency TEXT, price_input TEXT, price_output TEXT, price_cacheRead TEXT, price_cacheWrite TEXT,
                    price_capturedAt INTEGER, priceCaptured INTEGER NOT NULL, PRIMARY KEY(recordId,scopeId),
                    FOREIGN KEY(recordId) REFERENCES metric_runs(recordId) ON UPDATE NO ACTION ON DELETE CASCADE)""")
            }
        }
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS metric_requests (
                    recordId TEXT NOT NULL, requestId TEXT NOT NULL, scopeId TEXT NOT NULL, requestOrder INTEGER NOT NULL,
                    providerId TEXT NOT NULL, modelId TEXT NOT NULL, name TEXT NOT NULL, round INTEGER, attempt INTEGER NOT NULL,
                    startedAt INTEGER NOT NULL, startedElapsedMs INTEGER NOT NULL, completedAt INTEGER, completedElapsedMs INTEGER,
                    status TEXT NOT NULL, httpCode INTEGER, failureCode TEXT NOT NULL, responseMs INTEGER, firstTextMs INTEGER, totalMs INTEGER,
                    usage_inputTokens INTEGER, usage_outputTokens INTEGER, usage_reasoningTokens INTEGER, usage_cachedTokens INTEGER,
                    usage_contextTokens INTEGER, usage_uncachedInputTokens INTEGER, usage_cacheReadTokens INTEGER,
                    usage_cacheWriteTokens INTEGER, usage_billableOutputTokens INTEGER,
                    price_revision TEXT, price_currency TEXT, price_input TEXT, price_output TEXT, price_cacheRead TEXT, price_cacheWrite TEXT,
                    price_capturedAt INTEGER, priceCaptured INTEGER NOT NULL,
                    PRIMARY KEY(recordId, requestId),
                    FOREIGN KEY(recordId) REFERENCES metric_runs(recordId) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_metric_requests_recordId ON metric_requests(recordId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_metric_requests_scopeId ON metric_requests(scopeId)")
            }
        }
        @Volatile private var instance: AgentMetricsDatabase? = null
        fun get(context: Context): AgentMetricsDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AgentMetricsDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3).enableMultiInstanceInvalidation().build().also { instance = it }
        }
        @VisibleForTesting internal fun closeForTests() = synchronized(this) { instance?.close(); instance = null }
    }
}
