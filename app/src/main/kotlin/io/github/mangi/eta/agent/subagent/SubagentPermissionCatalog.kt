package io.github.mangi.eta.agent.subagent

import io.github.mangi.eta.agent.context.PersonalSearchTools
import io.github.mangi.eta.agent.model.AgentFileToolCatalog
import io.github.mangi.eta.agent.model.AgentPhoneToolCatalog
import io.github.mangi.eta.agent.model.AgentTerminalToolCatalog

/** Stable permission groups shared by the profile editor and the conversational Agent tool. */
internal data class SubagentPermissionCategory(
    val id: String,
    val title: String,
    val summary: String,
    val tools: Set<String>,
    val risk: Risk,
) {
    enum class Risk { READ, WRITE, DESTRUCTIVE }
}

internal object SubagentPermissionCatalog {
    private val fileWrites = AgentFileToolCatalog.names.filterTo(linkedSetOf()) {
        it == "write_file" || it == "edit_file"
    }
    private val fileReads = (AgentFileToolCatalog.names - fileWrites).toSet()
    private val webTools = setOf("web_search", "fetch_url")
    private val memoryTools = SubagentProfile.READ_ONLY_TOOLS.filterTo(linkedSetOf()) {
        it.startsWith("memory_") || it.startsWith("character_memory_") || it.startsWith("skills_")
    }
    // These tools are appended by AgentToolCatalog independently of the legacy
    // READ_ONLY_TOOLS set. Keep them in the shared permission catalog as well, otherwise
    // meeting runs publish the schemas but AgentToolPolicy removes them before the model
    // sees them.
    private val skillDiscoveryTools = setOf("skills_list_curated", "skills_inspect_github")
    private val automationTools = setOf(
        "schedule_create", "schedule_list", "schedule_pause", "schedule_delete",
    )
    private val learningTools = setOf("learning_feedback")
    private val voiceTools = setOf("voice_synthesize")
    private val personalReadTools = (
        PersonalSearchTools.names +
            AgentPhoneToolCatalog.entries.filter { it.personal && !it.write }.map { it.name } +
            SubagentProfile.READ_ONLY_TOOLS.filter {
                it.startsWith("search_") || it == "read_personal_item" || it == "summarize_bills"
            }
        ).toSet()
    private val deviceReadTools = AgentPhoneToolCatalog.entries
        .filter { !it.write && !it.personal }
        .mapTo(linkedSetOf()) { it.name }
    private val deviceActionTools = AgentPhoneToolCatalog.entries
        .filter { it.write || it.direct && it.name !in deviceReadTools }
        .mapTo(linkedSetOf()) { it.name }
    private val classifiedReadTools = fileReads + webTools + memoryTools + personalReadTools + deviceReadTools
    private val generalReadTools = (SubagentProfile.READ_ONLY_TOOLS - classifiedReadTools).toSet()

    val categories: List<SubagentPermissionCategory> = listOf(
        SubagentPermissionCategory(
            id = "files-read",
            title = "文件读取与搜索",
            summary = "读取、列目录、查找和搜索文件内容",
            tools = fileReads,
            risk = SubagentPermissionCategory.Risk.READ,
        ),
        SubagentPermissionCategory(
            id = "web-read",
            title = "网络调研",
            summary = "搜索网页和抓取公开链接",
            tools = webTools,
            risk = SubagentPermissionCategory.Risk.READ,
        ),
        SubagentPermissionCategory(
            id = "device-read",
            title = "设备状态读取",
            summary = "查询应用、网络、日志、用量、位置、通知等状态",
            tools = deviceReadTools + generalReadTools,
            risk = SubagentPermissionCategory.Risk.READ,
        ),
        SubagentPermissionCategory(
            id = "personal-read",
            title = "个人资料查询",
            summary = "查询日程、联系人、消息、账单、媒体、笔记等个人数据",
            tools = personalReadTools,
            risk = SubagentPermissionCategory.Risk.READ,
        ),
        SubagentPermissionCategory(
            id = "memory-skills",
            title = "记忆与技能",
            summary = "读取长期记忆、角色记忆和已安装技能",
            tools = memoryTools,
            risk = SubagentPermissionCategory.Risk.READ,
        ),
        SubagentPermissionCategory(
            id = "skills-discovery",
            title = "技能目录调研",
            summary = "查看公开技能目录和仓库中的技能定义",
            tools = skillDiscoveryTools,
            risk = SubagentPermissionCategory.Risk.READ,
        ),
        SubagentPermissionCategory(
            id = "automation",
            title = "定时任务",
            summary = "查看、创建、暂停和删除 Nexus 定时任务",
            tools = automationTools,
            risk = SubagentPermissionCategory.Risk.WRITE,
        ),
        SubagentPermissionCategory(
            id = "learning-feedback",
            title = "运行反馈",
            summary = "记录用户明确提供的运行评分和纠正",
            tools = learningTools,
            risk = SubagentPermissionCategory.Risk.WRITE,
        ),
        SubagentPermissionCategory(
            id = "voice-generation",
            title = "语音生成",
            summary = "使用当前语音设置把文本合成为 WAV 音频文件",
            tools = voiceTools,
            risk = SubagentPermissionCategory.Risk.WRITE,
        ),
        SubagentPermissionCategory(
            id = "files-write",
            title = "文件修改",
            summary = "创建、覆盖和精确编辑文件",
            tools = fileWrites,
            risk = SubagentPermissionCategory.Risk.WRITE,
        ),
        SubagentPermissionCategory(
            id = "terminal",
            title = "终端与脚本",
            summary = "执行命令、后台任务和长驻服务",
            tools = AgentTerminalToolCatalog.names,
            risk = SubagentPermissionCategory.Risk.DESTRUCTIVE,
        ),
        SubagentPermissionCategory(
            id = "device-action",
            title = "设备操作与写入",
            summary = "修改开关、闹钟、便签、日程和其他设备状态",
            tools = deviceActionTools,
            risk = SubagentPermissionCategory.Risk.DESTRUCTIVE,
        ),
    )

    val allTools: Set<String> = categories.flatMapTo(linkedSetOf()) { it.tools }

    fun category(id: String): SubagentPermissionCategory? = categories.firstOrNull { it.id == id }

    fun expandCategoryIds(ids: Iterable<String>): List<String> = ids
        .flatMap { id -> category(id)?.tools.orEmpty() }
        .distinct()
        .sorted()

    fun selectedCategoryIds(tools: Set<String>): Set<String> = categories
        .filter { category -> category.tools.isNotEmpty() && tools.containsAll(category.tools) }
        .mapTo(linkedSetOf()) { it.id }

    fun partialCategoryIds(tools: Set<String>): Set<String> = categories
        .filter { category -> category.tools.any(tools::contains) && !tools.containsAll(category.tools) }
        .mapTo(linkedSetOf()) { it.id }

    fun defaultTools(profile: SubagentProfile): Set<String> {
        val explicit = profile.explicitTools
        return when {
            explicit == null -> allTools.filterTo(linkedSetOf()) { profile.isToolAllowed(it) }
            "*" in explicit -> allTools
            else -> explicit.filterTo(linkedSetOf()) { it !in SubagentProfile.FORBIDDEN_TOOLS }
        }
    }

    fun normalizeSavedTools(tools: Set<String>): List<String> = when {
        tools.isEmpty() -> emptyList()
        tools.containsAll(allTools) -> listOf("*")
        else -> tools.sorted()
    }
}
