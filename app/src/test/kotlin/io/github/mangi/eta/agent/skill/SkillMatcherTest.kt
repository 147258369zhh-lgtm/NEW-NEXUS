package io.github.mangi.eta.agent.skill

import io.github.mangi.eta.agent.runtime.AgentToolPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun smallInstalledLibrariesAreRankedAndUnavailableEntriesNeverWin() {
        val other = skill("other", "日历", "维护日程")
        val pdf = skill("pdf", "PDF 工具", "制作 PDF 报告")
        val unavailable = pdf.copy(id = "disabled", enabled = false)
        assertEquals(listOf(pdf, other), SkillMatcher.rankForTask(listOf(other, unavailable, pdf), "制作 PDF 报告"))
        assertTrue(SkillMatcher.rankForTask(listOf(pdf), "PDF", 0).isEmpty())
        assertEquals(0, SkillMatcher.score("日历", skill("empty", "", "")))
    }

    @Test
    fun oneTaskCanSelectMultipleSkillsWhileUnrelatedSkillsStayOut() {
        val pdf = skill("pdf-tools", "PDF 工具", "制作 PDF 报告")
        val excel = skill("excel-tools", "Excel 表格", "整理工作簿和表格")
        val calendar = skill("calendar", "日历", "维护日程")

        val plan = SkillCoordination.plan(
            skills = listOf(calendar, pdf, excel),
            task = "用 PDF 工具和 Excel 表格整理这份报告",
        )

        assertEquals(setOf("pdf-tools", "excel-tools"), plan.selected.map { it.id }.toSet())
        assertTrue(plan.blocked.isEmpty())
        assertFalse(plan.requiresConfirmation)
    }

    @Test
    fun declaredConflictUsesPriorityAndBlocksTheLoser() {
        val pdf = skill("pdf-tools", "PDF 工具", "制作 PDF 报告")
            .copy(declaration = SkillDeclaration(priority = 7, conflictsWith = listOf("excel-tools")))
        val excel = skill("excel-tools", "Excel 表格", "整理工作簿和表格")
            .copy(declaration = SkillDeclaration(priority = 2))

        val plan = SkillCoordination.plan(listOf(pdf, excel), "用 PDF 工具和 Excel 表格整理报告")

        assertEquals(listOf("pdf-tools"), plan.selected.map { it.id })
        assertEquals("SKILL_CONFLICT_SUPERSEDED", plan.blocked.single().code)
        assertEquals("excel-tools", plan.blocked.single().id)
        assertFalse(plan.requiresConfirmation)
    }

    @Test
    fun equalPriorityConflictFailsClosedUntilUserMentionsOneSkill() {
        val pdf = skill("pdf-tools", "PDF 工具", "制作 PDF 报告")
            .copy(declaration = SkillDeclaration(priority = 3, conflictsWith = listOf("excel-tools")))
        val excel = skill("excel-tools", "Excel 表格", "整理工作簿和表格")
            .copy(declaration = SkillDeclaration(priority = 3))

        val unresolved = SkillCoordination.plan(listOf(pdf, excel), "用 PDF 工具和 Excel 表格整理报告")
        assertTrue(unresolved.requiresConfirmation)
        assertTrue(unresolved.selected.isEmpty())
        assertEquals(setOf("pdf-tools", "excel-tools"), unresolved.blocked.map { it.id }.toSet())
        assertEquals("SKILL_CONFLICT_REQUIRES_CONFIRMATION", unresolved.blocked.first().code)

        val confirmed = SkillCoordination.plan(
            listOf(pdf, excel),
            "请只用 Excel 表格整理销售数据，忽略 PDF 方案",
        )
        assertEquals(listOf("excel-tools"), confirmed.selected.map { it.id })
        assertEquals("pdf-tools", confirmed.blocked.single().id)
        assertFalse(confirmed.requiresConfirmation)
    }

    @Test
    fun declaredSkillToolsNeverWidenTheParentPolicy() {
        val entry = skill("pdf-tools", "PDF 工具", "制作 PDF 报告")
            .copy(declaration = SkillDeclaration(allowedTools = listOf("read_file", "terminal_exec")))
        val skillPolicy = requireNotNull(SkillCoordination.toolPolicy(entry))
        val parent = AgentToolPolicy(listOf("read_file", "web_search"))

        val effective = skillPolicy.intersect(parent)
        assertTrue(effective.isAllowed("read_file"))
        assertFalse(effective.isAllowed("terminal_exec"))
        assertFalse(effective.isAllowed("web_search"))
        assertNull(SkillCoordination.toolPolicy(entry.copy(declaration = SkillDeclaration())))
    }

    @Test
    fun explicitlySelectedSkillIsUsedEvenWhenPromptDoesNotMatchItsIndex() {
        val writing = skill("writing", "Writing helper", "Improve prose")
        val calendar = skill("calendar", "Calendar", "Manage events")
        val plan = SkillCoordination.plan(
            skills = listOf(writing, calendar),
            task = "Summarize this error log",
            explicitlySelectedIds = listOf("writing"),
        )
        assertEquals(listOf("writing"), plan.selected.map { it.id })
        assertTrue(plan.blocked.isEmpty())
    }

    @Test
    fun explicitlySelectedConflictingSkillsStillFailClosed() {
        val left = skill("left", "Left", "Left workflow")
            .copy(declaration = SkillDeclaration(conflictsWith = listOf("right")))
        val right = skill("right", "Right", "Right workflow")
        val plan = SkillCoordination.plan(
            listOf(left, right), "Do the task", explicitlySelectedIds = listOf("left", "right"),
        )
        assertTrue(plan.requiresConfirmation)
        assertTrue(plan.selected.isEmpty())
        assertEquals(setOf("left", "right"), plan.blocked.map { it.id }.toSet())
    }

    @Test
    fun selectedSkillResolverRejectsDisabledRemovedAndOverLimitSelections() {
        val enabled = skill("enabled", "Enabled", "Available")
        val disabled = skill("disabled", "Disabled", "Unavailable").copy(enabled = false)
        assertEquals(listOf(enabled), SelectedSkillResolver.resolve(listOf(enabled), listOf("enabled")).skills)
        assertEquals("SELECTED_SKILL_UNAVAILABLE", SelectedSkillResolver.resolve(listOf(disabled), listOf("disabled")).error)
        assertEquals("SELECTED_SKILL_UNAVAILABLE", SelectedSkillResolver.resolve(listOf(enabled), listOf("removed")).error)
        assertEquals("SELECTED_SKILL_SELECTION_INVALID", SelectedSkillResolver.resolve(listOf(enabled), listOf("a", "b", "c", "d")).error)
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
