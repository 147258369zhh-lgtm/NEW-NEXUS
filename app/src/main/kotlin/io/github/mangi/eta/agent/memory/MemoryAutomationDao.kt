package io.github.mangi.eta.agent.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface MemoryAutomationDao {
    @Query("SELECT * FROM memory_entries ORDER BY updatedAt DESC LIMIT 1000")
    fun observeEntries(): Flow<List<MemoryEntry>>
    @Query("SELECT * FROM memory_entries ORDER BY updatedAt DESC")
    suspend fun entries(): List<MemoryEntry>
    @Query("SELECT * FROM memory_entries WHERE id = :id")
    suspend fun entry(id: String): MemoryEntry?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: MemoryEntry)
    @Update
    suspend fun update(entry: MemoryEntry)
    @Query("DELETE FROM memory_entries")
    suspend fun clearEntries()
    @Query("SELECT * FROM memory_jobs ORDER BY createdAt DESC LIMIT 40")
    fun observeJobs(): Flow<List<MemoryJob>>
    @Query("SELECT * FROM memory_jobs WHERE status = 'queued' ORDER BY createdAt LIMIT 1")
    suspend fun nextJob(): MemoryJob?
    @Query("SELECT COALESCE(SUM(attempts), 0) FROM memory_jobs WHERE attempts > 0 AND updatedAt >= :since")
    suspend fun attemptsSince(since: Long): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJob(job: MemoryJob): Long
    @Query("SELECT * FROM memory_jobs")
    suspend fun jobs(): List<MemoryJob>
    @Query("SELECT * FROM memory_jobs WHERE id = :id")
    suspend fun job(id: String): MemoryJob?
    @Query("DELETE FROM memory_jobs")
    suspend fun clearJobs()
    @Update
    suspend fun updateJob(job: MemoryJob)
    @Query("UPDATE memory_jobs SET status = 'queued', detail = 'interrupted' WHERE status = 'running'")
    suspend fun recoverJobs()
    @Query("DELETE FROM memory_jobs WHERE status NOT IN ('queued', 'running') AND createdAt < :before")
    suspend fun pruneJobs(before: Long)
    @Query("SELECT count(*) FROM memory_jobs WHERE status IN ('queued', 'running')")
    suspend fun queueSize(): Int
}
