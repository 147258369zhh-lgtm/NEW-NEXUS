package io.github.mangi.eta.agent.capability

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
class SkillUpdateStoreTest {
    private lateinit var store: SkillUpdateStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "capability-center/skill-updates.json").delete()
        store = SkillUpdateStore(context)
    }

    @Test
    fun roundTripsActionableMarketMetadata() {
        val item = SkillMarketItem(
            sourceId = "github-team",
            sourceName = "Team Skills",
            sourceType = SkillSourceType.GITHUB_REPOSITORY,
            trust = SkillSourceTrust.CURATED,
            skillId = "sheet-tools",
            displayName = "Sheet Tools",
            description = "Spreadsheet automation",
            version = "2.1.0",
            repository = "eta-labs/skills",
            ref = "a".repeat(40),
            path = "skills/sheet-tools",
            tags = listOf("excel", "sheets"),
            dependencies = listOf("python"),
            permissions = listOf("files.read"),
        )
        store.write(SkillUpdateState(checkedAt = 123L, updates = listOf(item), failedSourceCount = 1))

        val restored = store.read()
        assertEquals(123L, restored.checkedAt)
        assertEquals(1, restored.failedSourceCount)
        assertEquals(item, restored.updates.single())
    }

    @Test
    fun corruptFilesFailClosed() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "capability-center/skill-updates.json")
        file.parentFile?.mkdirs()
        file.writeText("not-json")

        assertEquals(SkillUpdateState(), store.read())
        assertTrue(store.read().updates.isEmpty())
    }
}
