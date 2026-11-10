package io.github.mangi.eta.fork.tasks

/** Validation boundary for schedules created from an Agent turn. */
internal object AgentSelfSchedulePolicy {
    const val MAX_PROMPT_CHARS = 8_000
    const val MAX_TITLE_CHARS = 80

    fun validate(title: String, prompt: String, confirmed: Boolean) {
        require(confirmed) { "创建定时任务前需要用户明确确认" }
        require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS) { "任务标题无效" }
        require(prompt.isNotBlank() && prompt.length <= MAX_PROMPT_CHARS) { "任务提示词无效" }
    }
}
