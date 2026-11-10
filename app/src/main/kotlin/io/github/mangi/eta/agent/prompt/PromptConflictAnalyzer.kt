package io.github.mangi.eta.agent.prompt

internal enum class PromptIssueLevel {
    ERROR,
    WARNING,
    INFO,
}

internal data class PromptIssue(
    val level: PromptIssueLevel,
    val code: String,
    val message: String,
    val templateId: String = "",
)

internal data class PromptAnalysisReport(
    val issues: List<PromptIssue>,
) {
    val errors: List<PromptIssue> get() = issues.filter { it.level == PromptIssueLevel.ERROR }
    val warnings: List<PromptIssue> get() = issues.filter { it.level == PromptIssueLevel.WARNING }
    val canSave: Boolean get() = errors.isEmpty()
}

/** Local, deterministic validation before a prompt can enter the runtime assembly path. */
internal object PromptConflictAnalyzer {
    private val authorityBypass = listOf(
        Regex("(忽略|跳过|绕过|无视).{0,12}(系统|安全|权限|工具|确认|审批)", RegexOption.IGNORE_CASE),
        Regex("(无需|不用|不必).{0,10}(确认|审批|用户同意)", RegexOption.IGNORE_CASE),
        Regex("(伪造|假装|虚构|谎称).{0,12}(工具|执行|结果|成功)", RegexOption.IGNORE_CASE),
        Regex("(获取|申请|提升|开启).{0,10}(root|管理员|超级权限)", RegexOption.IGNORE_CASE),
        Regex("ignore.{0,12}(system|safety|permission|confirmation)", RegexOption.IGNORE_CASE),
        Regex("fabricate.{0,12}(tool|result|success)", RegexOption.IGNORE_CASE),
    )
    private val macro = Regex("\\{\\{\\s*(char|user|persona|model|date)\\s*\\}\\}", RegexOption.IGNORE_CASE)

    fun analyzeTemplate(template: PromptTemplate): PromptAnalysisReport {
        val issues = mutableListOf<PromptIssue>()
        if (template.name.isBlank()) {
            issues += PromptIssue(PromptIssueLevel.ERROR, "TEMPLATE_NAME_EMPTY", "模板名称不能为空", template.id)
        }
        val enabledBlocks = template.blocks.filter(PromptBlock::enabled)
        if (enabledBlocks.none { it.content.isNotBlank() }) {
            issues += PromptIssue(
                PromptIssueLevel.ERROR,
                "TEMPLATE_NO_CONTENT",
                "至少需要一个已启用且包含正文的指令块",
                template.id,
            )
        }
        val duplicateIds = template.blocks.groupingBy(PromptBlock::id).eachCount().filterValues { it > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            issues += PromptIssue(
                PromptIssueLevel.ERROR,
                "TEMPLATE_DUPLICATE_BLOCK_ID",
                "模板包含重复的指令块 ID，请重新创建这些块",
                template.id,
            )
        }
        val normalizedBlocks = enabledBlocks.map { normalize(it.content) }.filter(String::isNotEmpty)
        normalizedBlocks.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
            issues += PromptIssue(
                PromptIssueLevel.WARNING,
                "TEMPLATE_DUPLICATE_CONTENT",
                "存在重复的启用指令块，会浪费上下文预算",
                template.id,
            )
        }
        enabledBlocks.forEach { block ->
            if (authorityBypass.any { it.containsMatchIn(block.content) }) {
                issues += PromptIssue(
                    PromptIssueLevel.WARNING,
                    "PROMPT_AUTHORITY_BYPASS",
                    "“${block.title.ifBlank { "未命名指令块" }}”试图绕过安全、权限、确认或真实执行边界",
                    template.id,
                )
            }
            if (macro.containsMatchIn(block.content)) {
                issues += PromptIssue(
                    PromptIssueLevel.INFO,
                    "PROMPT_MACRO",
                    "“${block.title.ifBlank { "未命名指令块" }}”包含角色宏，具体展开取决于运行作用域",
                    template.id,
                )
            }
        }
        if (template.characterCount > 32_000) {
            issues += PromptIssue(
                PromptIssueLevel.WARNING,
                "PROMPT_LARGE",
                "启用内容超过 32,000 字符，可能挤压对话、工具说明和记忆预算",
                template.id,
            )
        }
        return PromptAnalysisReport(issues.distinctBy { listOf(it.level, it.code, it.message, it.templateId) })
    }

    fun analyzeConfig(config: PromptInjectionConfig): PromptAnalysisReport {
        val issues = config.templates.flatMap { analyzeTemplate(it).issues }.toMutableList()
        val templates = config.templates.associateBy(PromptTemplate::id)
        val duplicateTargets = config.bindings.groupingBy { it.target.key }.eachCount().filterValues { it > 1 }.keys
        if (duplicateTargets.isNotEmpty()) {
            issues += PromptIssue(
                PromptIssueLevel.ERROR,
                "BINDING_DUPLICATE_TARGET",
                "同一作用范围存在多个绑定，运行结果不稳定",
            )
        }
        config.bindings.forEach { binding ->
            val template = templates[binding.templateId]
            when {
                template == null -> issues += PromptIssue(
                    PromptIssueLevel.ERROR,
                    "BINDING_TEMPLATE_MISSING",
                    "绑定引用了不存在的模板",
                    binding.templateId,
                )
                !template.enabled -> issues += PromptIssue(
                    PromptIssueLevel.WARNING,
                    "BINDING_TEMPLATE_DISABLED",
                    "“${template.name}”已绑定但当前被停用",
                    template.id,
                )
                !binding.enabled -> issues += PromptIssue(
                    PromptIssueLevel.INFO,
                    "BINDING_DISABLED",
                    "“${template.name}”的该作用范围绑定已停用",
                    template.id,
                )
            }
        }
        return PromptAnalysisReport(issues.distinctBy { listOf(it.level, it.code, it.message, it.templateId) })
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(Regex("[\\s\\p{P}]"), "")
}
