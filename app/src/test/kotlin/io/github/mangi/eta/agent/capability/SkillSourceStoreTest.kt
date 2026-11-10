package io.github.mangi.eta.agent.capability

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36, 37])
class SkillSourceStoreTest {
    private lateinit var store: SkillSourceStore

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "capability-center/skill-sources.json").delete()
        store = SkillSourceStore(context)
    }

    @Test
    fun startsWithCuratedSourceAndPersistsCustomCatalog() {
        val initial = store.load().single()
        assertEquals("openai-curated", initial.id)
        assertTrue(initial.builtIn)
        assertTrue(initial.enabled)

        val saved = store.add(
            type = SkillSourceType.HTTP_CATALOG,
            name = "团队目录",
            location = "https://skills.example.com/catalog.json",
            trust = SkillSourceTrust.COMMUNITY,
        )
        val custom = saved.single { !it.builtIn }

        assertEquals(2, store.load().size)
        assertEquals("团队目录", custom.name)
        assertEquals(SkillSourceTrust.COMMUNITY, custom.trust)
        assertFalse(store.delete(custom.id).any { it.id == custom.id })
    }

    @Test
    fun validatesCustomCatalogBoundariesAndProtectsBuiltinSource() {
        assertThrows(SkillSourceValidationException::class.java) {
            store.add(
                type = SkillSourceType.HTTP_CATALOG,
                name = "不安全目录",
                location = "http://skills.example.com/catalog.json",
            )
        }
        assertThrows(SkillSourceValidationException::class.java) {
            store.add(
                type = SkillSourceType.HTTP_CATALOG,
                name = "带凭据目录",
                location = "https://user:pass@skills.example.com/catalog.json",
            )
        }
        val builtin = store.load().single { it.builtIn }
        assertThrows(SkillSourceValidationException::class.java) {
            store.delete(builtin.id)
        }
        val disabled = store.setEnabled(builtin.id, false).single { it.id == builtin.id }
        assertFalse(disabled.enabled)
        assertTrue(store.resetBuiltinSources().single { it.id == builtin.id }.enabled)
    }
}
