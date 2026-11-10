package io.github.mangi.eta.fork.a2a

internal object A2AOutputSanitizer {
    private val dsmlBlock = Regex("(?is)<[^>]*DSML[^>]*>.*?</[^>]*DSML[^>]*>")
    private val invokeName = Regex("(?i)invoke\\s+name\\s*=\\s*[\"']([^\"']+)[\"']")
    private val marker = Regex("(?i)(?:<)?[|｜]{1,2}\\s*DSML\\s*[|｜]{1,2}")
    private val splitDsmlBlock = Regex("(?is)<[^>]*DSML[^>]*>.*?<[^>]*DSML[^>]*>")
    private val protocolFragment = Regex("(?i)(?:\\b(?:calls?|invoke)\\b\\s*>|\\bname\\s*=|\\)\\s*>|\\binvoke\\s*\\(?)")

    fun sanitize(raw: String, access: A2AToolAccess): String {
        val text = raw.trim()
        if (!marker.containsMatchIn(text) && !text.contains("DSML", ignoreCase = true)) return text
        val invoked = invokeName.findAll(text).map { it.groupValues[1].trim() }.toSet()
        val denied = invoked.filterNot(access.allowedTools::contains)
        // Some providers emit the private-use full-width bars instead of ASCII vertical bars.
        // Normalize only for parsing; the caller never receives this normalized markup.
        val normalized = text.replace('｜', '|')
        val cleaned = normalized.replace(dsmlBlock, "")
            .replace(splitDsmlBlock, "")
            .replace(marker, "")
            .replace(Regex("(?i)<[^>]*DSML[^>]*>"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        val cleanTextRemains = cleaned.isNotBlank() &&
            cleaned.any { it.isLetterOrDigit() || it in "，。！？,.!?" } &&
            !protocolFragment.containsMatchIn(cleaned)
        if (cleanTextRemains) {
            return cleaned
        }
        return if (denied.isNotEmpty()) {
            "该工具未授权：${denied.joinToString("、")}。本次未执行任何操作。"
        } else {
            "模型尝试调用未被运行时解析的工具，本次未执行任何操作。"
        }
    }
}
