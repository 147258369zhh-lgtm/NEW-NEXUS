package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight

@Immutable
internal data class CodeHighlightPalette(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val function: Color,
    val type: Color,
)

/**
 * Lightweight lexical highlighting for the languages that commonly appear in chat answers.
 * It works directly on the parsed text and therefore keeps selection, copy and streaming intact.
 */
internal fun highlightCode(
    source: String,
    language: String?,
    palette: CodeHighlightPalette,
): AnnotatedString {
    if (source.isEmpty() || language.isNullOrBlank()) return AnnotatedString(source)
    val normalized = language.lowercase().substringBefore(' ').substringBefore('{')
    val keywords = KEYWORDS[normalized] ?: return AnnotatedString(source)
    val lineComment = LINE_COMMENTS[normalized]
    val blockComment = normalized !in setOf("python", "py", "ruby", "yaml", "yml", "shell", "bash")
    val builder = AnnotatedString.Builder(source)
    var index = 0
    while (index < source.length) {
        val char = source[index]
        if (lineComment != null && source.startsWith(lineComment, index)) {
            val end = source.indexOf('\n', index).let { if (it < 0) source.length else it }
            builder.addStyle(SpanStyle(color = palette.comment), index, end)
            index = end
            continue
        }
        if (blockComment && source.startsWith("/*", index)) {
            val end = source.indexOf("*/", index + 2).let { if (it < 0) source.length else it + 2 }
            builder.addStyle(SpanStyle(color = palette.comment), index, end)
            index = end
            continue
        }
        if (char == '"' || char == '\'' || char == '`') {
            val end = stringEnd(source, index, char)
            builder.addStyle(SpanStyle(color = palette.string), index, end)
            index = end
            continue
        }
        if (char.isDigit()) {
            var end = index + 1
            while (end < source.length && (source[end].isLetterOrDigit() || source[end] in "._xX")) end += 1
            builder.addStyle(SpanStyle(color = palette.number), index, end)
            index = end
            continue
        }
        if (char.isLetter() || char == '_') {
            var end = index + 1
            while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '_')) end += 1
            val word = source.substring(index, end)
            val style = when {
                word in keywords -> SpanStyle(color = palette.keyword, fontWeight = FontWeight.Medium)
                word.firstOrNull()?.isUpperCase() == true && normalized !in setOf("shell", "bash") ->
                    SpanStyle(color = palette.type)
                source.getOrNull(end) == '(' -> SpanStyle(color = palette.function)
                else -> null
            }
            if (style != null) builder.addStyle(style, index, end)
            index = end
            continue
        }
        index += 1
    }
    return builder.toAnnotatedString()
}

private fun stringEnd(source: String, start: Int, quote: Char): Int {
    var index = start + 1
    while (index < source.length) {
        if (source[index] == '\\') {
            index = (index + 2).coerceAtMost(source.length)
            continue
        }
        if (source[index] == quote) return index + 1
        index += 1
    }
    return source.length
}

private val LINE_COMMENTS = mapOf(
    "kotlin" to "//", "kt" to "//", "java" to "//", "javascript" to "//", "js" to "//",
    "typescript" to "//", "ts" to "//", "tsx" to "//", "jsx" to "//", "c" to "//",
    "cpp" to "//", "c++" to "//", "rust" to "//", "go" to "//", "swift" to "//",
    "python" to "#", "py" to "#", "ruby" to "#", "shell" to "#", "bash" to "#",
    "yaml" to "#", "yml" to "#",
)

private val KEYWORDS = mapOf(
    "kotlin" to setOf(
        "as", "break", "class", "continue", "data", "do", "else", "false", "for", "fun",
        "if", "in", "interface", "is", "null", "object", "override", "package", "private",
        "protected", "public", "return", "sealed", "super", "this", "throw", "true", "try",
        "typealias", "val", "var", "when", "while", "by", "catch", "constructor", "delegate",
        "dynamic", "field", "file", "finally", "get", "import", "init", "param", "property",
        "receiver", "set", "setparam", "where", "actual", "abstract", "annotation", "companion",
        "const", "crossinline", "expect", "external", "final", "infix", "inline", "inner", "lateinit",
        "noinline", "open", "operator", "out", "reified", "suspend", "tailrec", "vararg",
    ),
    "kt" to setOf("class", "data", "fun", "if", "else", "when", "val", "var", "return", "suspend"),
    "java" to setOf("class", "interface", "enum", "public", "private", "protected", "static", "final", "void", "new", "return", "if", "else", "for", "while", "try", "catch", "finally", "throws", "extends", "implements", "package", "import", "true", "false", "null"),
    "javascript" to setOf("const", "let", "var", "function", "return", "if", "else", "for", "while", "class", "new", "async", "await", "import", "export", "from", "throw", "try", "catch", "finally", "true", "false", "null", "undefined"),
    "js" to setOf("const", "let", "var", "function", "return", "if", "else", "for", "while", "class", "new", "async", "await"),
    "typescript" to setOf("const", "let", "var", "function", "return", "interface", "type", "enum", "class", "new", "async", "await", "import", "export", "from", "public", "private", "readonly", "extends", "implements"),
    "ts" to setOf("const", "let", "interface", "type", "return", "async", "await"),
    "python" to setOf("def", "class", "return", "if", "elif", "else", "for", "while", "import", "from", "as", "try", "except", "finally", "with", "lambda", "yield", "async", "await", "True", "False", "None", "in", "is", "not", "and", "or"),
    "py" to setOf("def", "class", "return", "if", "elif", "else", "for", "while", "import", "from", "as", "try", "except", "finally", "with", "lambda", "yield", "async", "await", "True", "False", "None"),
    "rust" to setOf("fn", "let", "mut", "pub", "struct", "enum", "impl", "trait", "use", "mod", "match", "if", "else", "loop", "while", "for", "in", "return", "async", "await", "move", "where", "crate", "self", "Self", "true", "false"),
    "go" to setOf("func", "package", "import", "var", "const", "type", "struct", "interface", "map", "chan", "go", "defer", "return", "if", "else", "for", "range", "switch", "case", "default", "select", "nil", "true", "false"),
    "c" to setOf("int", "char", "float", "double", "void", "struct", "enum", "typedef", "const", "static", "return", "if", "else", "for", "while", "switch", "case", "break", "continue", "sizeof"),
    "cpp" to setOf("class", "struct", "namespace", "template", "typename", "public", "private", "protected", "const", "static", "return", "if", "else", "for", "while", "auto", "new", "delete", "nullptr", "true", "false"),
    "c++" to setOf("class", "struct", "namespace", "template", "typename", "public", "private", "const", "static", "return", "if", "else", "for", "while", "auto"),
    "json" to setOf("true", "false", "null"),
    "sql" to setOf("SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "ORDER", "BY", "HAVING", "INSERT", "INTO", "UPDATE", "DELETE", "CREATE", "TABLE", "ALTER", "DROP", "AND", "OR", "NOT", "NULL", "AS", "LIMIT"),
    "bash" to setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function", "in", "export", "local", "readonly"),
    "shell" to setOf("if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac", "function", "in", "export", "local", "readonly"),
)
