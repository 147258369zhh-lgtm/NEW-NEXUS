package io.github.mangi.eta.agent.runtime

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "local_model_metric_samples")
internal data class LocalModelMetricEntity(
    @PrimaryKey val sampleId: String,
    val runId: String,
    val requestId: String,
    val providerId: String,
    val modelId: String,
    val modelFormat: String,
    val backend: String,
    val backendDetail: String?,
    val loadMs: Long?,
    val firstTokenMs: Long?,
    val promptTokens: Int?,
    val generatedTokens: Int?,
    val tokensPerSecond: Double?,
    val promptCacheHitTokens: Int?,
    val promptCacheReusedTokens: Int?,
    val kvCacheUsedTokens: Int?,
    val kvCacheCapacityTokens: Int?,
    val contextWindow: Int?,
    val prefillMs: Long?,
    val decodeMs: Long?,
    val totalMs: Long?,
    val finishReason: String?,
    val failureCode: String?,
    val startedAt: Long,
    val completedAt: Long,
)

internal data class LocalModelMetricAggregateRow(
    val sampleCount: Long,
    val averageLoadMs: Double?,
    val averageFirstTokenMs: Double?,
    val averageTokensPerSecond: Double?,
    val totalPromptTokens: Long?,
    val totalGeneratedTokens: Long?,
    val totalCacheHitTokens: Long?,
    val totalKvUsedTokens: Long?,
    val totalKvCapacityTokens: Long?,
)

@Dao
internal interface LocalModelMetricsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(sample: LocalModelMetricEntity)

    @Query("""
        SELECT * FROM local_model_metric_samples
        WHERE (:modelId IS NULL OR modelId = :modelId)
        ORDER BY completedAt DESC LIMIT :limit
    """)
    fun samplesFlow(modelId: String?, limit: Int): Flow<List<LocalModelMetricEntity>>

    @Query("""
        SELECT COUNT(*) AS sampleCount,
               AVG(loadMs) AS averageLoadMs,
               AVG(firstTokenMs) AS averageFirstTokenMs,
               AVG(tokensPerSecond) AS averageTokensPerSecond,
               SUM(promptTokens) AS totalPromptTokens,
               SUM(generatedTokens) AS totalGeneratedTokens,
               SUM(promptCacheHitTokens) AS totalCacheHitTokens,
               SUM(kvCacheUsedTokens) AS totalKvUsedTokens,
               SUM(kvCacheCapacityTokens) AS totalKvCapacityTokens
        FROM local_model_metric_samples
        WHERE (:modelId IS NULL OR modelId = :modelId)
    """)
    fun aggregateFlow(modelId: String?): Flow<LocalModelMetricAggregateRow?>

    @Query("""
        DELETE FROM local_model_metric_samples
        WHERE sampleId NOT IN (
            SELECT sampleId FROM local_model_metric_samples
            ORDER BY completedAt DESC LIMIT :keep
        )
    """)
    fun prune(keep: Int)

    @Query("DELETE FROM local_model_metric_samples") fun clear()
}

@Database(entities = [LocalModelMetricEntity::class], version = 1, exportSchema = false)
internal abstract class LocalModelMetricsDatabase : RoomDatabase() {
    abstract fun dao(): LocalModelMetricsDao

    companion object {
        const val NAME = "eta_local_model_metrics.db"

        @Volatile private var instance: LocalModelMetricsDatabase? = null

        fun get(context: Context): LocalModelMetricsDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                LocalModelMetricsDatabase::class.java,
                NAME,
            ).build().also { instance = it }
        }

        @androidx.annotation.VisibleForTesting
        internal fun closeForTests() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
