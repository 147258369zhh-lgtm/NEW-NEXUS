package io.github.mangi.eta.agent.subagent

import org.json.JSONObject

internal data class SubagentRoleSections(
    val description: String = "",
    val whenToUse: String = "",
    val responsibilities: String = "",
    val workflow: String = "",
    val outputContract: String = "",
    val failurePolicy: String = "",
    val additionalInstructions: String = "",
)

internal object SubagentRoleAuthoring {
    private const val RESPONSIBILITIES = "职责范围"
    private const val WORKFLOW = "工作流程"
    private const val OUTPUT = "输出要求"
    private const val FAILURE = "失败与边界"
    private const val ADDITIONAL = "补充角色指令"

    fun compose(sections: SubagentRoleSections): String {
        val additional = sections.additionalInstructions.trim()
        if (listOf(
                sections.responsibilities,
                sections.workflow,
                sections.outputContract,
                sections.failurePolicy,
            ).all(String::isBlank)
        ) {
            return additional
        }
        return buildList {
            section(RESPONSIBILITIES, sections.responsibilities)?.let(::add)
            section(WORKFLOW, sections.workflow)?.let(::add)
            section(OUTPUT, sections.outputContract)?.let(::add)
            section(FAILURE, sections.failurePolicy)?.let(::add)
            section(ADDITIONAL, additional)?.let(::add)
        }.joinToString("\n\n")
    }

    fun parseRoleInstruction(value: String): SubagentRoleSections {
        val text = value.trim()
        if (text.isBlank()) return SubagentRoleSections()
        val headings = listOf(RESPONSIBILITIES, WORKFLOW, OUTPUT, FAILURE, ADDITIONAL)
            .associateWith { heading ->
                Regex("(?ms)^##\\s+${Regex.escape(heading)}\\s*\\n(.*?)(?=^##\\s+|\\z)")
                    .find(text)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    .orEmpty()
            }
        if (headings.values.all(String::isBlank)) {
            return SubagentRoleSections(additionalInstructions = text)
        }
        return SubagentRoleSections(
            responsibilities = headings[RESPONSIBILITIES].orEmpty(),
            workflow = headings[WORKFLOW].orEmpty(),
            outputContract = headings[OUTPUT].orEmpty(),
            failurePolicy = headings[FAILURE].orEmpty(),
            additionalInstructions = headings[ADDITIONAL].orEmpty(),
        )
    }

    fun parseGeneratedJson(raw: String): SubagentRoleSections {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        require(start >= 0 && end > start) { "模型没有返回可识别的结构化草稿" }
        val json = JSONObject(cleaned.substring(start, end + 1))
        return SubagentRoleSections(
            description = json.optString("description").trim(),
            whenToUse = json.optString("whenToUse").trim(),
            responsibilities = json.optString("responsibilities").trim(),
            workflow = json.optString("workflow").trim(),
            outputContract = json.optString("outputContract").trim(),
            failurePolicy = json.optString("failurePolicy").trim(),
            additionalInstructions = json.optString("additionalInstructions").trim(),
        )
    }

    fun generationPrompt(seed: String): String = buildString {
        appendLine("你正在为手机 Agent 创建可复用的 Subagent 角色草稿。")
        appendLine("根据用户提供的任务描述、被选中的对话内容或操作记录，提炼稳定、边界清晰的职责。")
        appendLine("只输出一个 JSON 对象，不要 Markdown 代码围栏，不要额外解释。字段必须是：")
        appendLine("description：一句话简介；whenToUse：何时应派发；responsibilities：职责与禁止事项；")
        appendLine("workflow：按顺序执行的步骤；outputContract：最终交给主 Agent 的内容结构；")
        appendLine("failurePolicy：缺少信息、工具失败和超出权限时如何处理；additionalInstructions：其他约束。")
        appendLine("所有字段使用简洁中文。不要把一次性任务固化，不要授予 GUI 独占、自我修改或递归派发能力。")
        appendLine()
        appendLine("<reference>")
        append(seed.trim().take(12_000))
        appendLine()
        appendLine("</reference>")
    }

    private fun section(title: String, body: String): String? =
        body.trim().takeIf(String::isNotEmpty)?.let { "## $title\n$it" }
}
