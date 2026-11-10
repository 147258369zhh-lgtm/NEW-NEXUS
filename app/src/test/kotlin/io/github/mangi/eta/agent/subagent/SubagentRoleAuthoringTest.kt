package io.github.mangi.eta.agent.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentRoleAuthoringTest {
    @Test
    fun structured_role_round_trips_without_losing_sections() {
        val source = SubagentRoleSections(
            description = "分析账单并核对异常。",
            whenToUse = "需要检查账单明细时。",
            responsibilities = "读取账单，只报告有证据的异常。",
            workflow = "先汇总，再按金额与周期核对。",
            outputContract = "输出异常列表、证据与建议。",
            failurePolicy = "数据不足时停止结论并说明缺口。",
            additionalInstructions = "不要修改原始记录。",
        )

        val restored = SubagentRoleAuthoring.parseRoleInstruction(SubagentRoleAuthoring.compose(source))

        assertEquals(source.responsibilities, restored.responsibilities)
        assertEquals(source.workflow, restored.workflow)
        assertEquals(source.outputContract, restored.outputContract)
        assertEquals(source.failurePolicy, restored.failurePolicy)
        assertEquals(source.additionalInstructions, restored.additionalInstructions)
    }

    @Test
    fun generated_json_accepts_markdown_fence_and_surrounding_text() {
        val generated = SubagentRoleAuthoring.parseGeneratedJson(
            """
            这里是结果：
            ```json
            {
              "description": "整理资料",
              "whenToUse": "需要固定格式摘要时",
              "responsibilities": "只处理提供的资料",
              "workflow": "提取→核对→输出",
              "outputContract": "事实、来源、结论",
              "failurePolicy": "来源冲突时标注",
              "additionalInstructions": "不要猜测"
            }
            ```
            """.trimIndent(),
        )

        assertEquals("整理资料", generated.description)
        assertEquals("提取→核对→输出", generated.workflow)
        assertEquals("不要猜测", generated.additionalInstructions)
    }

    @Test
    fun legacy_plain_prompt_is_preserved_as_additional_instruction() {
        val parsed = SubagentRoleAuthoring.parseRoleInstruction("你是账单分析员，只输出结构化结论。")

        assertTrue(parsed.responsibilities.isBlank())
        assertEquals("你是账单分析员，只输出结构化结论。", parsed.additionalInstructions)
        assertEquals("你是账单分析员，只输出结构化结论。", SubagentRoleAuthoring.compose(parsed))
    }
}
