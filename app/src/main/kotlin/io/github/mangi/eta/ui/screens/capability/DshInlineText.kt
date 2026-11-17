package io.github.mangi.eta.ui.screens.capability

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/** Protocol text permits emphasis and inline code; links never acquire a click handler. */
internal fun dshInlineText(source: String, codeBackground: Color): AnnotatedString {
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(source)
    val output = AnnotatedString.Builder()
    fun raw(node: ASTNode) = source.substring(node.startOffset, node.endOffset)
    fun append(node: ASTNode) {
        when (node.type) {
            MarkdownElementTypes.MARKDOWN_FILE, MarkdownElementTypes.PARAGRAPH -> node.children.forEach(::append)
            MarkdownElementTypes.STRONG -> output.withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                node.children.filter { it.type != MarkdownTokenTypes.EMPH }.forEach(::append)
            }
            MarkdownElementTypes.CODE_SPAN -> {
                val text = raw(node)
                val delimiter = text.takeWhile { it == '`' }
                var content = text.removePrefix(delimiter).removeSuffix(delimiter).replace('\n', ' ')
                if (content.startsWith(' ') && content.endsWith(' ') && content.any { it != ' ' }) content = content.drop(1).dropLast(1)
                output.withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(content) }
            }
            MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.IMAGE -> {
                val link = if (node.type == MarkdownElementTypes.IMAGE) node.children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK } ?: node else node
                val label = link.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                if (label != null) label.children.filter { it.type != MarkdownTokenTypes.LBRACKET && it.type != MarkdownTokenTypes.RBRACKET }.forEach(::append)
                else output.append(raw(node))
            }
            else -> output.append(raw(node))
        }
    }
    append(root)
    return output.toAnnotatedString()
}
