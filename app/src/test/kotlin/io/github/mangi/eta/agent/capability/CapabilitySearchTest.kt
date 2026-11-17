package io.github.mangi.eta.agent.capability

import io.github.zhh_lgtm.nexus.application.api.CapabilitySummary
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import org.junit.Assert.*
import org.junit.Test

class CapabilitySearchTest {
    private fun row(id: String, description: String = "执行 Shell 命令", available: Boolean = true) =
        CapabilitySummary(id, "native", description, EffectClass.READ, available, false)

    @Test fun linuxPackageWordsFindTheExistingTerminalRatherThanInventingTools() {
        val results = CapabilitySearch.rank("Linux Python", listOf(row("read_file"), row("terminal")))
        assertEquals(listOf("terminal"), results.map { it.id })
    }

    @Test fun exactAndMultipleTermsRankMatchingLiveToolsBeforeUnrelatedOrUnavailableOnes() {
        val results = CapabilitySearch.rank("calendar search", listOf(
            row("old_calendar", "calendar search", false), row("calendar_search", "calendar search events"),
            row("file_search", "search text"), row("terminal")))
        assertEquals("calendar_search", results.first().id)
        assertEquals("old_calendar", results.last().id)
        assertFalse(results.any { it.id == "terminal" })
    }

    @Test fun chineseAndSkillAliasesRemainMetadataSearchOnly() {
        assertEquals("skills_list", CapabilitySearch.rank("技能库", listOf(row("skills_list"))).single().id)
        assertTrue(CapabilitySearch.rank("不存在的服务", listOf(row("terminal"))).isEmpty())
        assertTrue(CapabilitySearch.rank(" ", listOf(row("terminal"))).isEmpty())
    }
}
