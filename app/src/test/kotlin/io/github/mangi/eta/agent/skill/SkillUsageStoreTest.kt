package io.github.mangi.eta.agent.skill

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37])
class SkillUsageStoreTest {
    private lateinit var store: SkillUsageStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "capability-center/skill-usage.json").delete()
        store = SkillUsageStore(context)
    }

    @Test
    fun recordsDeduplicatedOutcomesWithoutTaskContent() {
        store.recordRun(listOf("pdf-tools", "pdf-tools", "invalid id"), successful = true, now = 100L)
        store.recordRun(listOf("pdf-tools"), successful = false, now = 200L)

        val record = store.readAll().getValue("pdf-tools")
        assertEquals(2, record.useCount)
        assertEquals(1, record.successCount)
        assertEquals(1, record.failureCount)
        assertEquals(200L, record.lastUsedAt)
        assertEquals(setOf("pdf-tools"), store.readAll().keys)
    }

    @Test
    fun corruptRecordsFailClosedAndEntriesCanBeRemoved() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "capability-center/skill-usage.json")
        file.parentFile?.mkdirs()
        file.writeText("{broken")
        assertTrue(store.readAll().isEmpty())

        store.recordRun(listOf("safe-skill"), true, 10L)
        assertEquals(1, store.readAll()["safe-skill"]?.useCount)
        store.remove("safe-skill")
        assertFalse("safe-skill" in store.readAll())
    }

    @Test fun concurrentOwnersCountEachActualRunOnceAndUnknownOutcomesDoNotBecomeFailures() {
        val context = RuntimeEnvironment.getApplication()
        val threads = (1..12).map { index -> kotlin.concurrent.thread {
            val value = SkillUsageStore(context)
            value.recordRun(listOf("skill-one"), null, index.toLong(), "run-$index")
            value.recordRun(listOf("skill-one"), null, index.toLong(), "run-$index")
        } }
        threads.forEach { it.join(3_000); assertFalse(it.isAlive) }
        val result = store.readAll().getValue("skill-one")
        assertEquals(12, result.useCount); assertEquals(0, result.failureCount); assertEquals(0, result.successCount)
        val json = File(context.filesDir, "capability-center/skill-usage.json").readText()
        assertFalse(json.contains("run-"))
    }
}
