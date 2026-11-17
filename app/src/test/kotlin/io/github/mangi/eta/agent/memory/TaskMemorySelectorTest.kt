package io.github.mangi.eta.agent.memory

import org.junit.Assert.*
import org.junit.Test

class TaskMemorySelectorTest {
    @Test fun unknownWindowUsesConservativeMemoryBudget() {
        assertEquals(TaskMemorySelector.budget(16_000), TaskMemorySelector.budget(null))
        assertTrue(TaskMemorySelector.budget(null) < TaskMemorySelector.budget(128_000))
    }
    @Test fun shortCoreRemainsResidentInSmallWindowWithoutAQueryMatch() {
        val revision = "12345678" + "a".repeat(56)
        val core = TaskMemorySelector.documentItems("global", "# 核心记忆\n默认用中文回答", revision).single()
        val result = TaskMemorySelector.select(listOf(core), setOf("global"), "", 4096)
        assertEquals(listOf(core), result.selected)
        assertTrue(result.render().contains("MEMORY.md@12345678"))
        assertFalse(result.render().contains(revision))
        assertEquals("MEMORY.md@$revision", result.selected.single().source)
        assertTrue(result.render().length <= TaskMemorySelector.budget(4096))
    }

    @Test fun filtersOwnershipBeforeRankingAndDeduplicatesAcrossPools() {
        val items = listOf(
            TaskMemoryItem("core", "global", "默认使用中文回答", "doc", core = true),
            TaskMemoryItem("other", "project:other", "修复 Gradle timeout", "经验库"),
            TaskMemoryItem("match", "project:mine", "修复 Gradle timeout", "doc"),
            TaskMemoryItem("duplicate", "project:mine", "修复 Gradle timeout", "审核"),
            TaskMemoryItem("irrelevant", "global", "整理相册中的照片", "经验库"),
        )
        val result = TaskMemorySelector.select(items, setOf("global", "project:mine"), "Gradle timeout", 128000)
        assertEquals(listOf("core", "match"), result.selected.map { it.id })
        assertEquals("scope", result.omitted["other"])
        assertEquals("duplicate", result.omitted["duplicate"])
        assertEquals("unrelated", result.omitted["irrelevant"])
    }

    @Test fun oneSharedBudgetSkipsOversizedEntriesWithoutCuttingRules() {
        val huge = TaskMemoryItem("huge", "global", "Gradle " + "x".repeat(1000), "doc")
        val small = TaskMemoryItem("small", "global", "Gradle 检查失败后先读取日志", "经验库")
        val result = TaskMemorySelector.select(listOf(huge, small), setOf("global"), "Gradle", 4096)
        assertEquals(listOf(small), result.selected)
        assertTrue(result.render().length <= TaskMemorySelector.budget(4096))
        assertEquals("budget", result.omitted["huge"])
        assertTrue(TaskMemorySelector.budget(1024) < 100)
    }

    @Test fun deduplicationPreservesDistinctPathsAndCase() {
        val items = listOf(
            TaskMemoryItem("first", "global", "Use config/a-b", "doc"),
            TaskMemoryItem("path", "global", "Use config/ab", "doc"),
            TaskMemoryItem("case", "global", "Use config/A-b", "doc"),
            TaskMemoryItem("duplicate", "global", "Use  config/a-b", "review"),
        )
        val result = TaskMemorySelector.select(items, setOf("global"), "config", 128000)
        assertEquals(listOf("first", "path", "case"), result.selected.map { it.id })
        assertEquals("duplicate", result.omitted["duplicate"])
    }

    @Test fun blankQueryOnlyKeepsSmallCoreAndDoesNotFillExperienceQuota() {
        val core = TaskMemoryItem("core", "global", "默认中文", "doc", true)
        val experience = TaskMemoryItem("exp", "global", "先读取日志再修改配置", "经验库")
        assertEquals(listOf(core), TaskMemorySelector.select(listOf(core, experience), setOf("global"), "", 128000).selected)
    }

    @Test fun documentUnitsKeepBulletContinuationsAndLegacyPreambleSearchable() {
        val items = TaskMemorySelector.documentItems("global", "旧文档前言\n\n# 核心记忆\n- 中文回答\n  只解释必要细节\n- 不要上传\n\n# 自定义\nGradle 超时恢复", "rev")
        assertEquals(4, items.size)
        assertFalse(items.first().core)
        assertTrue(items[1].content.contains("只解释必要细节"))
        assertTrue(items[1].core)
        assertTrue(TaskMemorySelector.relevance("Gradle超时", items.last().content) > 0)
    }
}
