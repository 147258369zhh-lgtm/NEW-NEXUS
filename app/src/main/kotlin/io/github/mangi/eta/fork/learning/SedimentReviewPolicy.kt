package io.github.mangi.eta.fork.learning

import io.github.mangi.eta.agent.memory.MemoryAutomationPolicy

internal data class SedimentReviewDecision(
    val approved: Boolean,
    val score: Double,
    val reasonCodes: List<String>,
    val summary: String,
)

/**
 * Deterministic software-butler gate for memory and learned rules.
 *
 * It runs before anything appears in the user's approval queue. The gate rejects low-information
 * summaries, repeated filler, unsupported automatic facts and procedural entries with no action.
 */
internal object SedimentReviewPolicy {
    private val lowValue = listOf(
        Regex("^(好的|收到|完成|已处理|测试成功|继续|谢谢|暂无|未知|不确定|无法确定|没有更多信息|无有效信息|本次任务已完成|操作成功)[了。！!\\s]*$", RegexOption.IGNORE_CASE),
        Regex("^(用户|助手|agent).{0,8}(正在|已经)?.{0,8}(对话|使用|运行|完成操作)$", RegexOption.IGNORE_CASE),
        Regex("^(以上|上述|以下).{0,6}(内容|信息|结果)$", RegexOption.IGNORE_CASE),
        Regex("^nothing to (remember|save|learn)$", RegexOption.IGNORE_CASE),
    )
    private val repeatedFiller = Regex("(.{1,6})\\1{3,}", RegexOption.DOT_MATCHES_ALL)
    private val actionWords = Regex(
        "(运行|执行|检查|验证|读取|写入|打开|关闭|创建|删除|修改|更新|提交|等待|重试|回滚|安装|导出|导入|搜索|记录|先|后|然后|before|after|run|check|verify|read|write|open|close|create|delete|update|retry|rollback|install|export|search)",
        RegexOption.IGNORE_CASE,
    )
    private val preferenceWords = Regex(
        "(偏好|喜欢|习惯|要求|希望|以后|始终|默认|不要|prefer|always|never|use|avoid)",
        RegexOption.IGNORE_CASE,
    )
    private val failureWords = Regex(
        "(失败|错误|异常|超时|冲突|崩溃|恢复|error|failed|exception|timeout|crash|recover)",
        RegexOption.IGNORE_CASE,
    )
    private val concreteSignal = Regex(
        "(\\d|[A-Za-z_]{2,}|[/\\\\][^\\s]+|`[^`]+`|项目名为|文件名为|版本为|端口为|路径为|命令为)",
    )
    private val genericEvidence = Regex(
        "^(用户原话|来自对话|系统输出|自动提取|测试内容|fixture|test|unknown|none)$",
        RegexOption.IGNORE_CASE,
    )

    fun reviewLearning(
        kind: LearningKind,
        title: String,
        content: String,
        evidence: String,
        sourceRef: String,
        confidence: Double,
        hasConflict: Boolean,
    ): SedimentReviewDecision {
        val reasons = mutableListOf<String>()
        var score = 0.45
        val cleanContent = content.trim()
        val cleanEvidence = evidence.trim()
        if (title.trim().length < 2) reasons += "标题过短"
        if (cleanContent.length < 8) reasons += "内容过短，缺少可复用信息"
        if (lowValue.any { it.matches(cleanContent) }) reasons += "只有完成状态或空泛描述"
        if (isRepetitive(cleanContent)) reasons += "重复填充内容没有沉淀价值"
        if (MemoryAutomationPolicy.sensitive("$title\n$cleanContent\n$cleanEvidence")) reasons += "包含敏感信息"
        val automatic = sourceRef.startsWith("conversation:") || sourceRef.startsWith("memory:") ||
            sourceRef.startsWith("automation:") || sourceRef.startsWith("run:")
        if (automatic && !hasUsefulEvidence(cleanEvidence)) reasons += "自动提取缺少可追溯证据"
        if (cleanEvidence.isNotBlank() && !genericEvidence.matches(cleanEvidence.trim())) score += 0.18
        if (concreteSignal.containsMatchIn("$title $cleanContent")) score += 0.13
        score += ((confidence.coerceIn(0.0, 1.0) - 0.5) * 0.18)
        when (kind) {
            LearningKind.PROCEDURE -> {
                if (!actionWords.containsMatchIn(cleanContent)) reasons += "步骤没有可执行动作"
                score += 0.12
                if (cleanContent.length >= 18) score += 0.08
            }
            LearningKind.PREFERENCE -> {
                if (!preferenceWords.containsMatchIn(cleanContent)) reasons += "没有明确偏好或长期要求"
                else score += 0.13
            }
            LearningKind.FAILURE_FIX -> {
                if (!failureWords.containsMatchIn("$title $cleanContent")) reasons += "缺少失败现象或恢复条件"
                if (!actionWords.containsMatchIn(cleanContent)) reasons += "修复经验没有可执行步骤"
                if (cleanContent.length >= 18) score += 0.08
            }
            LearningKind.FACT -> {
                if (!concreteSignal.containsMatchIn(cleanContent) && !hasUsefulEvidence(cleanEvidence)) {
                    reasons += "事实缺少具体对象或可追溯证据"
                }
            }
        }
        if (hasConflict) score -= 0.08
        val approved = reasons.isEmpty() && score >= MIN_SCORE
        if (reasons.isEmpty() && !approved) reasons += "综合质量分不足"
        return decision(approved, score, reasons)
    }

    fun reviewMemory(
        topic: String,
        content: String,
        evidence: String,
        source: String,
        confidence: Double = 0.7,
        hasConflict: Boolean = false,
    ): SedimentReviewDecision {
        val reasons = mutableListOf<String>()
        var score = 0.43
        val cleanContent = content.trim()
        val cleanEvidence = evidence.trim()
        if (topic.trim().length < 2) reasons += "主题过短"
        if (cleanContent.length < 7) reasons += "内容过短，缺少记忆价值"
        if (lowValue.any { it.matches(cleanContent) }) reasons += "只有完成状态或空泛描述"
        if (isRepetitive(cleanContent)) reasons += "重复填充内容没有记忆价值"
        if (MemoryAutomationPolicy.sensitive("$topic\n$cleanContent\n$cleanEvidence")) reasons += "包含敏感信息"
        val automatic = source.startsWith("conversation:") || source.startsWith("memory:") ||
            source.startsWith("automation:") || source.startsWith("run:")
        if (automatic && !hasUsefulEvidence(cleanEvidence)) reasons += "自动提取缺少原文证据"
        if (cleanEvidence.isNotBlank() && !genericEvidence.matches(cleanEvidence.trim())) score += 0.20
        if (concreteSignal.containsMatchIn("$topic $cleanContent")) score += 0.14
        if (preferenceWords.containsMatchIn(cleanContent)) score += 0.10
        score += ((confidence.coerceIn(0.0, 1.0) - 0.5) * 0.18)
        if (hasConflict) score -= 0.06
        val approved = reasons.isEmpty() && score >= MIN_SCORE
        if (reasons.isEmpty() && !approved) reasons += "综合质量分不足"
        return decision(approved, score, reasons)
    }

    private fun isRepetitive(content: String): Boolean {
        val normalized = MemoryAutomationPolicy.normalize(content)
        if (normalized.length >= 18) {
            val ratio = normalized.toSet().size.toDouble() / normalized.length
            if (ratio < 0.16) return true
        }
        return repeatedFiller.containsMatchIn(content)
    }

    private fun hasUsefulEvidence(evidence: String): Boolean {
        val clean = evidence.trim()
        return clean.length >= 6 && !genericEvidence.matches(clean)
    }

    private fun decision(approved: Boolean, score: Double, reasons: List<String>): SedimentReviewDecision {
        val bounded = score.coerceIn(0.0, 1.0)
        val summary = if (approved) {
            "软件管家审核通过，质量分 ${(bounded * 100).toInt()}%；等待人工批准。"
        } else {
            "软件管家审核拒绝：${reasons.joinToString("；")}"
        }
        return SedimentReviewDecision(approved, bounded, reasons.ifEmpty { listOf("quality-passed") }, summary)
    }

    private const val MIN_SCORE = 0.60
}
