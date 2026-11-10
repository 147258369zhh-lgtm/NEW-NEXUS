package io.github.mangi.eta.agent.skill

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37])
class SkillProvenanceStoreTest {
    private lateinit var store: SkillProvenanceStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "capability-center/skill-provenance.json").delete()
        store = SkillProvenanceStore(context)
    }

    @Test
    fun recordsUpdatesAndRemovesInstallationSources() {
        val first = provenance("pdf-tools", "source-a")
        store.record(first)
        assertEquals(first, store.readAll()["pdf-tools"])

        val updated = provenance("pdf-tools", "source-b").copy(version = "2.0.0")
        store.record(updated)
        assertEquals(updated, store.readAll()["pdf-tools"])

        store.remove("pdf-tools")
        assertTrue(store.readAll().isEmpty())
    }

    @Test
    fun ignoresCorruptRecordFileAndNeverExpandsTrust() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "capability-center/skill-provenance.json")
        file.parentFile?.mkdirs()
        file.writeText("{not-json")

        assertTrue(store.readAll().isEmpty())
        store.record(provenance("safe-skill", "source"))
        assertEquals("source", store.readAll()["safe-skill"]?.sourceId)
    }

    private fun provenance(skillId: String, sourceId: String) = SkillProvenance(
        skillId = skillId,
        sourceId = sourceId,
        sourceName = "Example",
        sourceType = "http_catalog",
        archiveSha256 = "b".repeat(64),
        version = "1.0.0",
        installedAt = 1234L,
    )
}
