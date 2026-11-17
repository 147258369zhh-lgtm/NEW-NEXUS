package io.github.mangi.eta.agent.skill

import java.io.File

/**
 * SKILL.md 解析器——从 YAML frontmatter + Markdown body 中提取结构化信息。
 *
 * 支持常见 YAML 字符串、注释、多行块、缩进子块；不构造对象或展开 YAML 引用。
 * 纯字符串处理，不依赖外部 YAML 库。
 */
internal object SkillParser {
    internal const val PROBLEMS_KEY = "__nexa_frontmatter_problems"

    /**
     * 读取并解析 [skillFile]，返回 frontmatter map + body string。
     * 文件不存在或不是文件时返回 null。
     */
    fun parseSkillFile(skillFile: File): ParsedSkillFile? {
        if (!skillFile.exists() || !skillFile.isFile) return null
        val raw = skillFile.readText().removePrefix("\uFEFF")
        val firstLineEnd = raw.indexOf('\n')
        if (firstLineEnd < 0 || raw.substring(0, firstLineEnd).trimEnd('\r') != "---") {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
        }
        val marker = Regex("(?m)^---\\r?$").find(raw, firstLineEnd + 1)
        if (marker == null) {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
        }
        val frontmatterText = raw.substring(firstLineEnd + 1, marker.range.first).trim('\n', '\r')
        val body = raw.substring(marker.range.last + 1).trim()
        return ParsedSkillFile(
            frontmatter = parseSimpleFrontmatter(frontmatterText),
            body = body,
        )
    }

    /**
     * 简单 YAML frontmatter 解析。
     *
     * 支持：
     * - `key: value` 单行
     * - `key: >` 折叠多行块
     * - `key: |` 字面多行块
     * - `key:` 后跟缩进子块
     */
    fun parseSimpleFrontmatter(frontmatter: String): Map<String, String> {
        if (frontmatter.isBlank()) return emptyMap()
        val lines = frontmatter.lines()
        val result = linkedMapOf<String, String>()
        val problems = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            if (rawLine.isBlank() || rawLine.trimStart().startsWith('#')) {
                index += 1
                continue
            }
            val keyMatch = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(rawLine)
            if (keyMatch == null) {
                problems += "不支持或无效的 YAML 行 ${index + 1}"
                index += 1
                continue
            }
            val key = keyMatch.groupValues[1]
            val value = stripInlineComment(keyMatch.groupValues[2]).trimEnd()
            if (key in result) problems += "YAML 字段重复：$key"
            if (YAML_BLOCK_SCALAR.matches(value)) {
                val literal = value.startsWith('|')
                val blockLines = mutableListOf<String>()
                index += 1
                val explicitIndent = value.firstOrNull(Char::isDigit)?.digitToInt()
                val blockIndent = explicitIndent ?: (index until lines.size).firstOrNull { lines[it].isNotBlank() }
                    ?.let { lines[it].takeWhile { character -> character == ' ' }.length } ?: 0
                while (index < lines.size && (lines[index].isBlank() ||
                        (blockIndent > 0 && lines[index].takeWhile { it == ' ' }.length >= blockIndent))) {
                    val next = lines[index]
                    blockLines += if (next.isBlank()) "" else next.drop(blockIndent).trimEnd()
                    index += 1
                }
                result[key] = if (literal) {
                    blockLines.joinToString("\n").trim()
                } else {
                    foldYamlLines(blockLines)
                }
                continue
            }
            if (value.isBlank()) {
                val builder = StringBuilder()
                index += 1
                while (index < lines.size && (lines[index].startsWith(' ') || lines[index].startsWith('\t'))) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(lines[index].trimEnd())
                    index += 1
                }
                result[key] = builder.toString().trim()
                continue
            }
            result[key] = unquoteScalar(value)
            index += 1
        }
        if (problems.isNotEmpty()) result[PROBLEMS_KEY] = problems.distinct().joinToString("；")
        return result
    }

    /** 保留引号内的 # 和冒号，支持 YAML 单引号转义及常见双引号转义。 */
    internal fun unquoteScalar(raw: String): String {
        val value = stripInlineComment(raw).trim()
        if (value.length < 2) return value
        if (value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.lastIndex).replace("''", "'")
        }
        if (value.first() != '"' || value.last() != '"') return value
        val body = value.substring(1, value.lastIndex)
        return buildString {
            var index = 0
            while (index < body.length) {
                val character = body[index++]
                if (character != '\\' || index == body.length) {
                    append(character)
                    continue
                }
                val escaped = body[index++]
                when (escaped) {
                    '0' -> append('\u0000')
                    'a' -> append('\u0007')
                    'b' -> append('\b')
                    't', '\t' -> append('\t')
                    'n' -> append('\n')
                    'v' -> append('\u000B')
                    'f' -> append('\u000C')
                    'r' -> append('\r')
                    'e' -> append('\u001B')
                    ' ', '"', '/', '\\' -> append(escaped)
                    'N' -> append('\u0085')
                    '_' -> append('\u00A0')
                    'L' -> append('\u2028')
                    'P' -> append('\u2029')
                    'x', 'u', 'U' -> {
                        val digits = when (escaped) { 'x' -> 2; 'u' -> 4; else -> 8 }
                        val hex = body.substring(index, minOf(index + digits, body.length))
                        val codePoint = hex.takeIf { it.length == digits }?.toLongOrNull(16)
                            ?.takeIf { it in 0L..0x10ffffL && it !in 0xd800L..0xdfffL }?.toInt()
                        if (codePoint == null) append('\\').append(escaped)
                        else {
                            append(String(Character.toChars(codePoint)))
                            index += digits
                        }
                    }
                    else -> append('\\').append(escaped)
                }
            }
        }
    }

    internal fun stripInlineComment(raw: String): String {
        var quote: Char? = null
        var index = 0
        while (index < raw.length) {
            val character = raw[index]
            when {
                quote == '"' && character == '\\' -> index += 1
                quote == '\'' && character == '\'' && raw.getOrNull(index + 1) == '\'' -> index += 1
                quote != null && character == quote -> quote = null
                quote == null && character in "\"'" && (index == 0 || raw[index - 1].isWhitespace() || raw[index - 1] in "[,{:") -> quote = character
                quote == null && character == '#' && (index == 0 || raw[index - 1].isWhitespace()) -> return raw.substring(0, index)
            }
            index += 1
        }
        return raw
    }

    /** `>` 折叠换行、保留空行形成的段落；尾部 chomp 对元数据没有语义差异。 */
    private fun foldYamlLines(lines: List<String>): String = buildString {
        var pendingBlankLines = 0
        lines.forEach { line ->
            if (line.isBlank()) {
                pendingBlankLines += 1
            } else {
                if (isNotEmpty()) {
                    if (pendingBlankLines == 0) append(' ')
                    else repeat(pendingBlankLines + 1) { append('\n') }
                }
                append(line)
                pendingBlankLines = 0
            }
        }
    }.trim()

    /**
     * 解析缩进子块为 key-value map（用于 metadata 字段）。
     */
    fun parseIndentedBlock(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val value = raw.trim()
        val lines = if (value.startsWith('{') && value.endsWith('}')) {
            splitFlowItems(value.substring(1, value.lastIndex))
        } else raw.lines()
        return lines.mapNotNull { line ->
            val match = Regex("^\\s*([A-Za-z0-9_.-]+):\\s*(.*)$").find(line) ?: return@mapNotNull null
            match.groupValues[1] to unquoteScalar(match.groupValues[2])
        }.toMap()
    }

    private fun splitFlowItems(raw: String): List<String> {
        val result = mutableListOf<String>()
        var quote: Char? = null
        var start = 0
        var index = 0
        while (index < raw.length) {
            val character = raw[index]
            when {
                quote == '"' && character == '\\' -> index += 1
                quote == '\'' && character == '\'' && raw.getOrNull(index + 1) == '\'' -> index += 1
                quote != null && character == quote -> quote = null
                quote == null && character in "\"'" -> quote = character
                quote == null && character == ',' -> { result += raw.substring(start, index); start = index + 1 }
            }
            index += 1
        }
        result += raw.substring(start)
        return result
    }

    /**
     * 从目录名和 frontmatter name 生成规范化的 skill id。
     */
    fun sanitizeSkillId(directoryName: String, frontmatterName: String?): String {
        val candidate = frontmatterName?.trim().takeUnless { it.isNullOrBlank() } ?: directoryName
        return candidate.lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .ifBlank { directoryName.lowercase() }
    }

    /**
     * 规范化查找字符串——用于 id/name/path 匹配。
     */
    fun normalizeSkillLookup(value: String): String =
        value.trim()
            .lowercase()
            .replace('\\', '/')
            .removeSuffix("/skill.md")
            .removeSuffix("/")
            .replace(Regex("\\s+"), "")
            .replace("-", "")
            .replace("_", "")

    private val YAML_BLOCK_SCALAR = Regex("[>|](?:[1-9][+-]?|[+-][1-9]?)?")

}
