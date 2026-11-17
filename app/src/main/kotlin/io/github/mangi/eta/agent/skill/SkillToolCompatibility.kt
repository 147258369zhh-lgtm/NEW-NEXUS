package io.github.mangi.eta.agent.skill

/** Tool aliases describe an upper bound only. The runtime must still intersect the user's policy. */
internal object SkillToolCompatibility {
    data class Result(val tools: List<String> = emptyList(), val problems: List<String> = emptyList())

    private val aliases = mapOf(
        "Read" to "read_file",
        "Write" to "write_file",
        "Edit" to "edit_file",
        "Glob" to "glob_files",
        "Grep" to "grep_files",
        "Bash" to "terminal",
        "WebFetch" to "fetch_url",
        "WebSearch" to "web_search",
    )
    private val nativeTool = Regex("[a-z][a-z0-9_]*(?:\\*)?")

    fun parse(raw: String?): Result {
        if (raw == null) return Result()
        val problems = mutableListOf<String>()
        var value = raw.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("- ")) SkillParser.stripInlineComment(trimmed).removePrefix("- ") else trimmed
        }.trim()
        if (value.startsWith('[') && value.endsWith(']')) value = value.substring(1, value.lastIndex)
        else if (value.startsWith('[') || value.endsWith(']')) problems += "allowed-tools 列表括号不完整"
        val tokens = mutableListOf<String>()
        var start = 0
        var quote: Char? = null
        var depth = 0
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when {
                quote == '"' && character == '\\' -> index += 1
                quote == '\'' && character == '\'' && value.getOrNull(index + 1) == '\'' -> index += 1
                quote != null && character == quote -> quote = null
                quote == null && character in "\"'" -> quote = character
                quote == null && character == '(' -> depth += 1
                quote == null && character == ')' -> { depth -= 1; if (depth < 0) problems += "allowed-tools 工具表达式括号不完整" }
                quote == null && depth == 0 && (character.isWhitespace() || character == ',' || character == ';') -> {
                    value.substring(start, index).takeIf(String::isNotBlank)?.let(tokens::add)
                    start = index + 1
                }
            }
            index += 1
        }
        value.substring(start).takeIf(String::isNotBlank)?.let(tokens::add)
        if (quote != null || depth != 0) problems += "allowed-tools 工具表达式引号或括号不完整"
        if (tokens.isEmpty()) problems += "allowed-tools 显式空白名单暂不支持，未按无限制运行"
        if (tokens.size > 32) problems += "allowed-tools 工具数量超过 32 项"
        val tools = tokens.take(32).map { token ->
            val tool = SkillParser.unquoteScalar(token)
            when {
                tool.length > 120 || tool.any(Char::isISOControl) -> {
                    problems += "allowed-tools 工具名称无效或过长"
                    tool.take(120)
                }
                tool in aliases -> aliases.getValue(tool)
                '(' in tool || ')' in tool -> {
                    problems += "暂不支持 allowed-tools 命令级限制：$tool；未扩大为整个终端权限"
                    tool
                }
                tool.startsWith("mcp__") || tool.startsWith("mcp:") || tool.startsWith("mcp.") -> {
                    problems += "暂不支持 allowed-tools MCP 表达式映射：$tool"
                    tool
                }
                tool == "*" || nativeTool.matches(tool) -> tool
                else -> {
                    problems += "暂不支持 allowed-tools 工具映射：$tool"
                    tool
                }
            }
        }.distinct()
        return Result(tools, problems.distinct())
    }
}
