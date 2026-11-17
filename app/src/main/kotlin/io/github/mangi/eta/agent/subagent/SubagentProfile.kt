package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.context.PersonalSearchTools
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentCloudToolCatalog
import io.github.mangi.eta.agent.model.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog
import org.json.JSONArray
import org.json.JSONObject

internal data class SubagentModelRequirements(
    val prefersReasoning: Boolean = false,
    val prefersStructuredOutput: Boolean = false,
    val prefersVision: Boolean = false,
)

/**
 * Subagent 角色定义与工具白名单（涵盖内置 Profile 与用户在 `agents/<name>.md` 定义的自定义 Profile）。
 *
 * 所有 Subagent 均剥夺 `subagent` / `subagent_profile` 自身（禁止递归派发与自修改）
 * 以及独占式物理屏幕 GUI 与交互式浏览器工具，确保多个 Subagent 并发运行时互不干扰。
 */
internal data class SubagentProfile(
    val name: String,
    val summary: String,
    val whenToUse: String,
    val toolHighlights: List<String>,
    val roleInstruction: String,
    private val allowWriteFilesAndTerminal: Boolean = false,
    val builtin: Boolean = true,
    val override: Boolean = false,
    val explicitTools: Set<String>? = null,
    val disallowedTools: Set<String> = emptySet(),
    val filePath: String? = null,
    val displayName: String = name,
    val modelRequirements: SubagentModelRequirements = SubagentModelRequirements(),
    val defaultContextWindow: Int? = null,
    val defaultMaxToolCalls: Int? = null,
) {
    fun buildSystemPrompt(): String =
        "$SUBAGENT_ROLE_PREFIX\n\n$roleInstruction"

    fun withRuntimeToolPolicy(
        toolsOverride: List<String>?,
        additionalDisallowedTools: Set<String>,
    ): SubagentProfile = copy(
        explicitTools = toolsOverride?.toSet() ?: explicitTools,
        disallowedTools = disallowedTools + additionalDisallowedTools,
        toolHighlights = if (toolsOverride != null) {
            toolsOverride.take(8)
        } else {
            toolHighlights
        },
    )

    fun filterTools(allTools: JSONArray): JSONArray = JSONArray().also { filtered ->
        for (index in 0 until allTools.length()) {
            val item = allTools.optJSONObject(index) ?: continue
            val toolName = item.optJSONObject("function")?.optString("name").orEmpty()
            if (isToolAllowed(toolName)) {
                filtered.put(JSONObject(item.toString()))
            }
        }
    }

    fun isToolAllowed(toolName: String): Boolean {
        if (toolName.isBlank() || toolName in FORBIDDEN_TOOLS || toolName in disallowedTools) {
            return false
        }
        val customAllowlist = explicitTools
        if (customAllowlist != null) {
            if ("*" in customAllowlist) return true
            return toolName in customAllowlist ||
                customAllowlist.any { it.endsWith("_*") && toolName.startsWith(it.removeSuffix("*")) } ||
                ("personal_data" in customAllowlist && (toolName in PersonalSearchTools.names || toolName in AgentPhoneToolCatalog.reads))
        }
        if (toolName in READ_ONLY_TOOLS || toolName in PersonalSearchTools.names || toolName in AgentPhoneToolCatalog.reads) {
            return true
        }
        if (!allowWriteFilesAndTerminal) return false
        return toolName in CODER_EXTRA_TOOLS || toolName.startsWith("mcp_")
    }

    companion object {
        const val TYPE_EXPLORE = "explore"
        const val TYPE_CODER = "coder"
        const val TYPE_PLAN = "plan"
        const val TYPE_MEETING_HOST = "meeting-host"
        const val DEFAULT_TYPE = TYPE_EXPLORE

        private const val SUBAGENT_ROLE_PREFIX =
            "你当前作为 Subagent（子智能体）运行。你收到的 user 消息均由主 Agent 派发，而非终端用户直接输入。" +
                "主 Agent 看不到你的中间思考与工具调用过程，只能看到你结束时的最后一条回复（final message）。" +
                "不要向终端用户直接提问；如果任务存在歧义或缺少前置条件，请在最终回复中向主 Agent 清晰说明。"

        val FORBIDDEN_TOOLS = setOf(
            "request_user_input",
            AgentSubagentToolCatalog.TOOL_NAME,
            AgentSubagentToolCatalog.PROFILE_TOOL_NAME,
            "observe_screen",
            "release_gui",
            "present_to_user",
            "tap",
            "tap_area",
            "tap_element",
            "long_press",
            "long_press_element",
            "swipe",
            "scroll",
            "scroll_element",
            "type_text",
            "input_text",
            "replace_text",
            "clear_text",
            "paste_text",
            "set_clipboard",
            "press_key",
            "open_system_panel",
            "wait",
            "wait_for_text",
            "wait_for_package",
            "launch_app",
            "open_uri",
            "browser_use",
            "memory_write",
            "character_memory_write",
            "skills_install_from_github",
            "terminal_daemon",
        )

        val READ_ONLY_TOOLS = setOf(
            "project_workspace_status",
            "read_file",
            "stat_file",
            "list_directory",
            "glob_files",
            "grep_files",
            "read_image",
            "web_search",
            "fetch_url",
            "get_current_context",
            "search_apps",
            "inspect_app",
            "device_status",
            "network_info",
            "top_memory_apps",
            "top_storage_apps",
            "get_setting",
            "get_logcat",
            "get_current_location",
            "get_device_environment",
            "list_alarms",
            "list_active_timers",
            "get_health_summary",
            "recent_notifications",
            "search_notification_history",
            "recent_app_activity",
            "app_usage_summary",
            "search_clipboard_history",
            "get_clipboard",
            "get_flashlight",
            "get_device_state",
            "get_display_state",
            "get_sound_state",
            "get_hotspot",
            "read_personal_item",
            "summarize_bills",
            "search_media",
            "search_audio",
            "search_recordings",
            "search_files",
            "search_calendar_events",
            "search_contacts",
            "search_call_history",
            "search_messages",
            "search_downloads",
            "search_notes",
            "search_coloros_recordings",
            "search_recording_summaries",
            "search_system_memories",
            "search_saved_places",
            "search_personal_orders",
            "search_qq_chat_images",
            "search_wechat_chat_images",
            "memory_get",
            "memory_search",
            "character_memory_get",
            "skills_list",
            "skills_read",
            "skills_read_resource",
            "skills_list_curated",
            "skills_inspect_github",
            "schedule_list",
        )

        private val CODER_EXTRA_TOOLS =
            (AgentFileToolCatalog.names + AgentTerminalToolCatalog.names + setOf("voice_synthesize")) - FORBIDDEN_TOOLS

        private val FILE_READS = (AgentFileToolCatalog.names - AgentFileToolCatalog.writeNames).toSet() + setOf("project_workspace_status", "read_image")
        private val KNOWLEDGE_READS = setOf("memory_get", "memory_search", "character_memory_get", "skills_list", "skills_read", "skills_read_resource")
        // PersonalSearchTools covers indexed DMP data and PhoneToolCatalog covers extensions;
        // the original SMS/calendar/media schemas are still owned by AgentDeviceToolCatalog.
        val PERSONAL_READ_TOOLS: Set<String> = PersonalSearchTools.names +
            AgentPhoneToolCatalog.entries.filter { it.personal && !it.write }.map { it.name } + setOf(
                "recent_notifications", "search_notification_history", "recent_app_activity", "app_usage_summary",
                "search_clipboard_history", "get_clipboard", "get_health_summary", "get_current_location",
                "search_media", "search_audio", "search_recordings", "search_files", "search_calendar_events",
                "search_contacts", "search_call_history", "search_messages", "search_downloads", "search_notes",
                "search_coloros_recordings", "search_recording_summaries", "search_system_memories",
                "search_saved_places", "search_personal_orders", "search_qq_chat_images", "search_wechat_chat_images",
            )
        private val RESEARCH_READS = FILE_READS + KNOWLEDGE_READS + setOf("web_search", "fetch_url")
        private val AUTHOR_TOOLS = RESEARCH_READS + AgentFileToolCatalog.writeNames
        private val SCRIPT_TOOLS = AUTHOR_TOOLS + (AgentTerminalToolCatalog.names - FORBIDDEN_TOOLS)

        private const val HANDOFF = "\n\n交接必须分别说明：成果与来源/文件、已实际执行的验证及结果、仍未验证的判断、阻塞与未完成事项。只操作派工指定的文件；发现其他成员同时编辑相同文件时先停止冲突写入并请主持协调。不得把计划中的检查说成已经完成。"

        val MEETING_HOST = SubagentProfile(
            name = TYPE_MEETING_HOST,
            displayName = "主持协调",
            summary = "会议的全局默认主 Agent，负责分工、依赖、分歧与最终交付",
            whenToUse = "会议主持、目标澄清、专家分工、证据比较、阶段推进及最终汇总；会议编排器负责调度。",
            toolHighlights = listOf("阶段编排", "结构化分工", "证据汇总"),
            roleInstruction = "你是专属会议主持。先确认目标、边界、交付和验收；按专业职责给成员明确目标、必要材料、负责文件、依赖、验收、预算与停止条件。避免多个成员同时修改同一文件。区分事实、建议、分歧与缺失证据；根据真实交接推进阶段，汇总可追溯的结论、失败与待办。不得冒充专家完成独立验证，也不能自行绕过会议编排器递归派工。阶段规定决定本轮能做什么。" + HANDOFF,
            explicitTools = FILE_READS + KNOWLEDGE_READS,
            modelRequirements = SubagentModelRequirements(prefersReasoning = true, prefersStructuredOutput = true),
            defaultContextWindow = 64_000,
            defaultMaxToolCalls = 8,
        )

        val EXPLORE = SubagentProfile(
            name = TYPE_EXPLORE,
            displayName = "文件探索",
            summary = "只读定位文件、调用链与可追溯证据",
            whenToUse = "快速定位工作区文件、符号、配置及调用关系；派工注明quick、medium或thorough。",
            toolHighlights = listOf("read_file", "glob_files", "grep_files"),
            roleInstruction = "你是文件探索专家（explore）。只读取当前任务工作区，定位相关文件、符号、调用链和直接证据，给出准确路径与待核实点，不修改文件，不默认查询个人资料。" + HANDOFF,
            allowWriteFilesAndTerminal = false,
            builtin = true,
            explicitTools = FILE_READS + KNOWLEDGE_READS,
            defaultContextWindow = 32_000,
            defaultMaxToolCalls = 16,
        )

        val CODER = SubagentProfile(
            name = TYPE_CODER,
            displayName = "编码实施",
            summary = "工程执行、文件编辑与终端任务",
            whenToUse = "General engineering and terminal agent with file-editing and shell execution tools; use it for delegated tasks that run commands, analyze APKs, edit files, or execute scripts.",
            toolHighlights = listOf("terminal", "read_file", "write_file", "edit_file", "glob_files", "grep_files", "web_search"),
            roleInstruction = "你是工程与终端执行 Subagent（coder）。执行代码开发、Git、Python/Node 脚本、文本处理或 APK 分析时，终端与文件工具均应显式使用 environment=\"linux\"；操作 Android 系统命令或设备状态时使用 environment=\"android\"；工作区与共享目录在两端对应同一物理文件夹，按操作性质选择环境与对应路径，无需重复搬运文件。你的最后一条消息就是交给主 Agent 的完整交接报告：" +
                "请清楚说明你执行了哪些命令或修改了哪些文件、关键输出与验证结果，以及任何未完成或需后续处理的事项。" + HANDOFF,
            allowWriteFilesAndTerminal = true,
            builtin = true,
            explicitTools = SCRIPT_TOOLS + "mcp_*",
            modelRequirements = SubagentModelRequirements(prefersReasoning = true),
            defaultContextWindow = 64_000,
            defaultMaxToolCalls = 32,
        )

        val PLAN = SubagentProfile(
            name = TYPE_PLAN,
            displayName = "方案规划",
            summary = "只读架构分析与分步方案设计",
            whenToUse = "Read-only planning agent. Use when you need a step-by-step implementation or execution plan, key file/data identification, and trade-off analysis before making changes.",
            toolHighlights = listOf("read_file", "glob_files", "grep_files", "web_search", "fetch_url", "skills_read"),
            roleInstruction = "你是只读规划 Subagent（plan）。你拥有只读检索能力，不得修改文件或执行破坏性操作。" +
                "请在最终回复中清晰列出：1. 已确认的上下文事实；2. 仍待核实的关键问题；3. 分步执行方案与风险权衡。每步交代依赖、负责文件、验收、预算和停止条件。" + HANDOFF,
            allowWriteFilesAndTerminal = false,
            builtin = true,
            explicitTools = RESEARCH_READS,
            modelRequirements = SubagentModelRequirements(prefersReasoning = true),
            defaultContextWindow = 64_000,
            defaultMaxToolCalls = 16,
        )

        private fun specialist(name: String, title: String, summary: String, trigger: String,
            instruction: String, tools: Set<String>, calls: Int = 16, context: Int = 64_000,
            requirements: SubagentModelRequirements = SubagentModelRequirements()) = SubagentProfile(
            name = name, displayName = title, summary = summary, whenToUse = trigger,
            toolHighlights = tools.take(6), roleInstruction = instruction + HANDOFF,
            explicitTools = tools, modelRequirements = requirements,
            defaultContextWindow = context, defaultMaxToolCalls = calls,
        )

        val BUILTIN_PROFILES: List<SubagentProfile> = listOf(
            MEETING_HOST, EXPLORE,
            specialist("research", "网络研究", "公开资料研究与来源日期", "需要公开资料、多个来源和长材料比较时。",
                "你是网络研究员。先列研究问题，搜索并读取原始来源，记录链接、发布日期和访问日期；区分原始研究、转载和观点，说明覆盖范围及来源分歧，不把搜索摘要当全文证据。", RESEARCH_READS, context = 96_000),
            specialist("fact-check", "事实核验", "逐项核验主张并标注可信状态", "需要独立核对具体主张、来源或时效性时。",
                "你是事实核验员。把待核验内容拆成可检验主张，独立取证并标注已证实、错误或未决；提供对应证据、日期与置信边界。不得仅重复作者结论；没有证据不能判定属实。", RESEARCH_READS,
                requirements = SubagentModelRequirements(prefersReasoning = true, prefersStructuredOutput = true)),
            PLAN, CODER,
            specialist("reviewer", "独立审查与验证", "有触发条件的缺陷与真实验收证据", "实现完成后独立审查、复现或运行范围明确的验证。",
                "你是独立审查员。先独立读实现与验收条件，再复现问题或运行必要的受限检查；报告触发条件、位置、影响和实测结果。终端仅用于已授权的验证，不借机修复或改写产品文件；无法验证须明确记录。", RESEARCH_READS + (AgentTerminalToolCatalog.names - FORBIDDEN_TOOLS), calls = 24,
                requirements = SubagentModelRequirements(prefersReasoning = true)),
            specialist("data-analyst", "数据分析", "口径清楚且可复算的数据结果", "计算、清洗、统计、数据图表或表格核对。",
                "你是数据分析员。确认数据范围、字段、单位和缺失值，记录计算口径，使用指定数据与脚本输出可复算结果；抽样复核、说明误差和样本限制。只写授权输出目录，不擅自覆盖原始数据。", SCRIPT_TOOLS, calls = 24, context = 96_000,
                requirements = SubagentModelRequirements(prefersReasoning = true)),
            specialist("writer", "写作", "面向指定读者的完整稿件", "已有材料需要组织成报告、方案、故事或其他完整稿件。",
                "你是写作者。先对齐读者、目的、体裁、长度与材料，形成完整可用稿件；引用和事实必须可追溯，缺失资料用明确占位，不编造经历、数据或引文。", AUTHOR_TOOLS, context = 96_000),
            specialist("editor", "编辑与翻译", "修订稿、译文与关键差异", "校订、润色、术语统一和跨语言翻译。",
                "你是编辑与译者。保持原意、事实、数字和限定条件，统一术语与语体；交付完整修订稿或译文及关键差异，歧义单列。不得把润色变成未经授权的内容扩写。", AUTHOR_TOOLS, context = 96_000),
            specialist("document-maker", "文档制作", "文档、表格、演示与PDF产物", "需要实际生成或编辑办公文档及可交付文件。",
                "你是文档制作员。先读取相关技能并检查文档工具、字体与运行环境，再生成指定格式产物；核对内容、公式、分页、溢出和渲染。云工具需服从本次上传与费用授权。交付文件路径及实际检查结果，不能只给制作计划。", SCRIPT_TOOLS + AgentCloudToolCatalog.names + "mcp_*", calls = 32, context = 96_000,
                requirements = SubagentModelRequirements(prefersVision = true)),
            specialist("personal-assistant", "个人资料助理", "授权类别内的日程、消息或账单摘要", "用户明确授权查询指定个人资料类别时。",
                "你是个人资料助理。仅查询本次明确需要的数据类别、时间段和对象，按来源与时间整理日程、消息或账单；不得扩查无关联系人、通知或消息，不替用户发送、删除或交易。缺少授权或材料向主Agent交接。", FILE_READS + KNOWLEDGE_READS + PERSONAL_READ_TOOLS, calls = 16),
            specialist("device-diagnostics", "设备诊断", "设备状态、日志与故障证据", "排查应用、网络、存储、内存或设备故障。",
                "你是设备诊断员。读取相关状态和日志，按症状、证据、可能原因和最小验证步骤报告；脱敏密钥与个人内容。只读检查，不修改设备设置、不清理数据；屏幕操作或修复交给主Agent按授权执行。", FILE_READS + KNOWLEDGE_READS + AgentPhoneToolCatalog.entries.filter { !it.write && !it.personal }.map { it.name }, calls = 16,
                requirements = SubagentModelRequirements(prefersReasoning = true)),
        )
        val BUILTIN_NAMES: Set<String> = BUILTIN_PROFILES.mapTo(linkedSetOf()) { it.name }
        val LEGACY_BUILTIN_NAMES: Set<String> = setOf(TYPE_EXPLORE, TYPE_CODER, TYPE_PLAN)

        fun resolve(
            name: String?,
            catalog: List<SubagentProfile> = SubagentProfileCatalog.listAll(),
            customRole: String? = null,
            customTools: List<String>? = null,
        ): SubagentProfile {
            val normalized = name?.trim()?.lowercase()
                ?.replace(Regex("[^a-z0-9_-]+"), "-")
                ?.trim('-')
                .orEmpty()
            val matched = if (normalized.isEmpty()) {
                catalog.firstOrNull { it.name == DEFAULT_TYPE } ?: EXPLORE
            } else {
                catalog.firstOrNull { it.name == normalized }
            }
            val cleanRole = customRole?.trim().orEmpty()
            val cleanTools = customTools
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && it !in FORBIDDEN_TOOLS }
                ?.distinct()
                .orEmpty()

            if (matched != null) {
                if (cleanRole.isEmpty() && cleanTools.isEmpty()) return matched
                return matched.copy(
                    roleInstruction = if (cleanRole.isNotEmpty()) {
                        "${matched.roleInstruction}\n\n补充角色要求：$cleanRole"
                    } else {
                        matched.roleInstruction
                    },
                    explicitTools = if (cleanTools.isNotEmpty()) cleanTools.toSet() else matched.explicitTools,
                    toolHighlights = if (cleanTools.isNotEmpty()) cleanTools.take(8) else matched.toolHighlights,
                )
            }

            // 未命中预设时，当场创建仅用于本次会话的临时专职 Subagent，不落盘保存。
            val ephemeralName = normalized.ifEmpty { "general" }
            val instruction = cleanRole.ifEmpty {
                "你是专职处理当前子任务的 Subagent（$ephemeralName）。请高效利用可用工具收集证据或完成操作，" +
                    "并在最后一条消息中向主 Agent 返回结构化、完整的结论摘要。"
            }
            return SubagentProfile(
                name = ephemeralName,
                summary = cleanRole.ifEmpty { "临时专职 Subagent" },
                whenToUse = cleanRole,
                toolHighlights = if (cleanTools.isNotEmpty()) {
                    cleanTools.take(8)
                } else {
                    listOf("read_file", "glob_files", "grep_files", "web_search", "personal_data", "terminal")
                },
                roleInstruction = instruction,
                allowWriteFilesAndTerminal = true,
                builtin = false,
                explicitTools = if (cleanTools.isNotEmpty()) cleanTools.toSet() else setOf("*"),
            )
        }
    }
}
