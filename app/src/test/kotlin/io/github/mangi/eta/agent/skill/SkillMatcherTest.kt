package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillMatcherTest {
    @Test
    fun ranksMatchingChineseAndEnglishSkillsBeforeUnrelatedSkills() {
        val unrelated = skill(
            id = "calendar-cleanup",
            name = "Calendar cleanup",
            description = "整理日历日程",
        )
        val pdf = skill(
            id = "pdf-tools",
            name = "PDF 工具",
            description = "读取、生成和校验 PDF 文档",
        )
        val excel = skill(
            id = "excel-tools",
            name = "Excel 表格",
            description = "处理工作簿和单元格",
        )
        val ranked = SkillMatcher.rankForTask(
            skills = listOf(unrelated, excel, pdf),
            task = "请用 PDF 工具检查这份文档",
            limit = 2,
        )

        assertEquals("pdf-tools", ranked.first().id)
        assertTrue(SkillMatcher.score("生成 PDF 报告", pdf) > SkillMatcher.score("生成 PDF 报告", unrelated))
    }

    @Test
    fun preservesExistingOrderWhenTaskHasNoSearchableTerms() {
        val values = List(5) { index ->
            skill("skill-" + index, "Skill " + index, "description")
        }

        assertEquals(values, SkillMatcher.rankForTask(values, "", limit = 5))
        assertEquals(values.take(2), SkillMatcher.rankForTask(values, "", limit = 2))
    }

    private fun skill(id: String, name: String, description: String) = SkillIndexEntry(
        id = id,
        name = name,
        description = description,
        rootPath = "/skills/" + id,
        skillFilePath = "/skills/" + id + "/SKILL.md",
        hasScripts = false,
        hasReferences = false,
        hasAssets = false,
        hasEvals = false,
    )
}
