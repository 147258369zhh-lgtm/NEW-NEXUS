package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.LinkMap

/**
 * 把 GFM AST 转成 [MarkdownDocument]。
 *
 * 同一会话内连续构建时，源码切片未变的顶层块直接复用上一次的实例：流式输出只改动
 * 尾部块，前面的段落、表格与代码块既不重新生成 AnnotatedString，组合期也因实例相同
 * 而跳过。行内样式或链接表变化（主题切换、终态补齐引用链接）时缓存整体失效。
 */
internal class MarkdownDocumentBuilder {
    private var cache = emptyMap<Int, CachedBlock>()
    private var cacheStyle: MarkdownInlineStyle? = null
    private var cacheLinks: LinkMap? = null

    fun build(
        root: ASTNode,
        source: String,
        links: LinkMap?,
        style: MarkdownInlineStyle,
    ): MarkdownDocument {
        if (style != cacheStyle || links !== cacheLinks) {
            cache = emptyMap()
            cacheStyle = style
            cacheLinks = links
        }
        val converter = BlockConverter(source, links, style)
        val nextCache = HashMap<Int, CachedBlock>()
        val blocks = root.children.mapNotNull { node ->
            val slice = source.substring(node.startOffset, node.endOffset)
            val reused = cache[node.startOffset]?.takeIf { it.source == slice }?.block
            val block = reused ?: converter.convert(node) ?: return@mapNotNull null
            nextCache[node.startOffset] = CachedBlock(slice, block)
            block
        }
        cache = nextCache
        return MarkdownDocument(blocks = blocks, revealKeys = blocks.revealKeys(), inlineStyle = style)
    }

    private class CachedBlock(val source: String, val block: MarkdownBlock)
}

private class BlockConverter(
    private val source: String,
    private val links: LinkMap?,
    private val style: MarkdownInlineStyle,
) {
    fun convert(node: ASTNode): MarkdownBlock? = when (node.type) {
        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.WHITE_SPACE,
        MarkdownTokenTypes.BLOCK_QUOTE,
        -> null

        MarkdownElementTypes.LINK_DEFINITION -> footnote(node)
        MarkdownElementTypes.PARAGRAPH -> paragraph(node)
        MarkdownElementTypes.ATX_1 -> heading(node, 1, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_2 -> heading(node, 2, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_3 -> heading(node, 3, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_4 -> heading(node, 4, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_5 -> heading(node, 5, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.ATX_6 -> heading(node, 6, MarkdownTokenTypes.ATX_CONTENT)
        MarkdownElementTypes.SETEXT_1 -> heading(node, 1, MarkdownTokenTypes.SETEXT_CONTENT)
        MarkdownElementTypes.SETEXT_2 -> heading(node, 2, MarkdownTokenTypes.SETEXT_CONTENT)
        MarkdownElementTypes.CODE_FENCE -> codeFence(node)
        MarkdownElementTypes.CODE_BLOCK -> indentedCode(node)
        MarkdownElementTypes.BLOCK_QUOTE -> MarkdownQuote(node.startOffset, children(node))
        GFMElementTypes.ALERT -> alert(node)
        MarkdownElementTypes.UNORDERED_LIST -> list(node, ordered = false)
        MarkdownElementTypes.ORDERED_LIST -> list(node, ordered = true)
        GFMElementTypes.TABLE -> table(node)
        MarkdownTokenTypes.HORIZONTAL_RULE -> MarkdownRule(node.startOffset)
        // 未支持的块（HTML 块等）按原文显示：宁可露出标记，也不能让内容凭空消失。
        else -> raw(node)
    }

    private fun children(node: ASTNode): List<MarkdownBlock> = node.children.mapNotNull(::convert)

    private fun paragraph(node: ASTNode): MarkdownBlock? {
        footnote(node)?.let { return it }
        val special = node.children.mapNotNull { child ->
            val image = if (child.type == MarkdownElementTypes.IMAGE) imageBlock(child, child)
                else if (child.type == MarkdownTokenTypes.HTML_TAG) htmlImage(child.startOffset, child.text()) else null
            image?.let { child to it }
        }
        if (special.isNotEmpty()) {
            val blocks = mutableListOf<MarkdownBlock>()
            var index = 0
            special.forEach { (child, image) ->
                val at = node.children.indexOf(child)
                val leading = inline(node.children.subList(index, at))
                if (leading.isNotBlank()) blocks += MarkdownParagraph(node.children.getOrNull(index)?.startOffset ?: node.startOffset, leading)
                blocks += image
                index = at + 1
            }
            val trailing = inline(node.children.drop(index))
            if (trailing.isNotBlank()) blocks += MarkdownParagraph(node.children.getOrNull(index)?.startOffset ?: node.endOffset, trailing)
            return if (blocks.size == 1) blocks.single() else MarkdownFlow(node.startOffset, blocks)
        }
        val meaningful = node.children.filterNot { it.isWhitespaceLike() }
        val math = meaningful.singleOrNull()?.takeIf { it.type == GFMElementTypes.BLOCK_MATH }
        if (math != null) {
            return MarkdownMath(node.startOffset, mathSource(math))
        }
        val image = meaningful.singleOrNull()?.takeIf { it.type == MarkdownElementTypes.IMAGE }
        if (image != null) return imageBlock(node, image)
        val text = inline(node.children)
        return if (text.isEmpty()) null else MarkdownParagraph(node.startOffset, text)
    }

    private fun heading(node: ASTNode, level: Int, contentType: org.intellij.markdown.IElementType): MarkdownBlock {
        val content = node.child(contentType)?.children.orEmpty()
        return MarkdownHeading(node.startOffset, level, inline(content))
    }

    /**
     * 围栏内容由 CODE_FENCE_CONTENT 与 EOL 交替组成；列表或引用里的缩进是独立的
     * WHITE_SPACE 节点，属于外层结构，拼接时跳过。
     */
    private fun codeFence(node: ASTNode): MarkdownBlock {
        val language = node.child(MarkdownTokenTypes.FENCE_LANG)
            ?.text()?.trim()?.substringBefore(' ')?.takeIf(String::isNotEmpty)
        val body = StringBuilder()
        var inBody = false
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.CODE_FENCE_END -> break
                MarkdownTokenTypes.EOL -> if (inBody) body.append('\n') else inBody = true
                MarkdownTokenTypes.CODE_FENCE_CONTENT -> if (inBody) body.append(child.text())
            }
        }
        if (body.endsWith('\n')) body.setLength(body.length - 1)
        return MarkdownCode(node.startOffset, language, AnnotatedString(body.toString()))
    }

    private fun indentedCode(node: ASTNode): MarkdownBlock {
        val body = buildString {
            node.children.forEach { child ->
                when (child.type) {
                    MarkdownTokenTypes.CODE_LINE -> append(child.text().removeIndent(4))
                    MarkdownTokenTypes.EOL -> append('\n')
                }
            }
        }.trimEnd('\n')
        return MarkdownCode(node.startOffset, language = null, text = AnnotatedString(body))
    }

    private fun alert(node: ASTNode): MarkdownBlock {
        val title = node.child(GFMTokenTypes.ALERT_TITLE)?.text().orEmpty()
        val kind = when (title.removeSurrounding("[!", "]").uppercase()) {
            "TIP" -> MarkdownAlertKind.Tip
            "IMPORTANT" -> MarkdownAlertKind.Important
            "WARNING" -> MarkdownAlertKind.Warning
            "CAUTION" -> MarkdownAlertKind.Caution
            else -> MarkdownAlertKind.Note
        }
        val blocks = node.children.filterNot { it.type == GFMTokenTypes.ALERT_TITLE }.mapNotNull(::convert)
        return MarkdownAlert(node.startOffset, kind, blocks)
    }

    private fun list(node: ASTNode, ordered: Boolean): MarkdownBlock {
        val itemNodes = node.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        val start = if (ordered) {
            itemNodes.firstOrNull()?.child(MarkdownTokenTypes.LIST_NUMBER)
                ?.text()?.takeWhile(Char::isDigit)?.toIntOrNull() ?: 1
        } else {
            null
        }
        val items = itemNodes.mapIndexed { index, item ->
            val blocks = item.children.mapNotNull { child ->
                when (child.type) {
                    MarkdownTokenTypes.LIST_BULLET,
                    MarkdownTokenTypes.LIST_NUMBER,
                    GFMTokenTypes.CHECK_BOX,
                    -> null
                    else -> convert(child)
                }
            }
            val explicitNumber = item.children.firstOrNull {
                it.type == MarkdownTokenTypes.LIST_NUMBER
            }?.text()?.takeWhile(Char::isDigit)?.toIntOrNull()
            MarkdownListItem(
                offset = item.startOffset,
                number = if (ordered) explicitNumber ?: start?.plus(index) else null,
                checked = item.child(GFMTokenTypes.CHECK_BOX)?.text()?.let { box -> 'x' in box || 'X' in box },
                blocks = blocks,
                markerRevealKey = blocks.firstRevealKey(),
            )
        }
        val loose = node.hasBlankLine() || itemNodes.any { it.hasBlankLine() }
        return MarkdownList(node.startOffset, ordered, loose, items)
    }

    private fun table(node: ASTNode): MarkdownBlock? {
        val header = node.child(GFMElementTypes.HEADER)?.cells() ?: return null
        val columns = header.size
        val alignments = node.children
            .firstOrNull { it.type == GFMTokenTypes.TABLE_SEPARATOR && '-' in it.text() }
            ?.text()?.parseAlignments(columns)
            ?: List(columns) { MarkdownColumnAlign.Start }
        // GFM：多余的单元格丢弃，缺少的补空，所有行与表头列数一致。
        val rows = node.children.filter { it.type == GFMElementTypes.ROW }.map { row ->
            val cells = row.cells()
            List(columns) { column ->
                cells.getOrNull(column) ?: MarkdownTableCell(row.endOffset, AnnotatedString(""))
            }
        }
        return MarkdownTable(node.startOffset, alignments, header, rows)
    }

    private fun ASTNode.cells(): List<MarkdownTableCell> = children
        .filter { it.type == GFMTokenTypes.CELL }
        .map { cell -> MarkdownTableCell(cell.startOffset, inline(cell.children)) }

    private fun raw(node: ASTNode): MarkdownBlock? {
        val text = node.text().trim()
        if (text.startsWith("<table", true)) mergedTable(node.startOffset, text)?.let { return it }
        if (text.startsWith("<img", true)) htmlImage(node.startOffset, text)?.let { return it }
        if (text.isEmpty()) return null
        FOOTNOTE_DEFINITION.matchEntire(text)?.let { match ->
            val label = match.groupValues[1]
            val body = match.groupValues[2].trim()
            return MarkdownFootnote(node.startOffset, label, AnnotatedString(body))
        }
        if (THEMATIC_BREAK.matches(text)) return MarkdownRule(node.startOffset)
        return MarkdownParagraph(node.startOffset, AnnotatedString(text))
    }

    private fun imageBlock(paragraph: ASTNode, image: ASTNode): MarkdownImage? {
        val link = image.children.firstOrNull { it.type in setOf(MarkdownElementTypes.INLINE_LINK, MarkdownElementTypes.FULL_REFERENCE_LINK, MarkdownElementTypes.SHORT_REFERENCE_LINK) } ?: return null
        val label = link.child(MarkdownElementTypes.LINK_TEXT) ?: link.child(MarkdownElementTypes.LINK_LABEL)
        val alt = label?.text()?.removeSurrounding("[", "]").orEmpty()
        val direct = link.child(MarkdownElementTypes.LINK_DESTINATION)?.text()?.removeSurrounding("<", ">")
        val reference = link.child(MarkdownElementTypes.LINK_LABEL)?.text() ?: label?.text()
        val url = direct ?: reference?.let { links?.getLinkInfo(it)?.destination?.toString() } ?: return null
        return safeImageUrl(decodeMarkdownText(url))?.let { MarkdownImage(paragraph.startOffset, it, decodeMarkdownText(alt)) }
    }

    private fun footnote(node: ASTNode): MarkdownFootnote? {
        val match = FOOTNOTE_DEFINITION.matchEntire(node.text().trim()) ?: return null
        val rendered = inline(node.children)
        val start = rendered.text.indexOf("]:").takeIf { it >= 0 }?.plus(2)
        val body = if (start != null) rendered.subSequence(start, rendered.length) else AnnotatedString(match.groupValues[2].trim())
        return MarkdownFootnote(node.startOffset, match.groupValues[1], body)
    }

    private fun htmlImage(offset: Int, text: String): MarkdownImage? {
        val element = org.jsoup.Jsoup.parseBodyFragment(text).select("img").singleOrNull() ?: return null
        val uri = safeImageUrl(element.attr("src")) ?: return null
        return MarkdownImage(offset, uri, element.attr("alt"))
    }

    private fun safeImageUrl(raw: String): String? = raw.trim().takeIf { url ->
        url.length <= 18 * 1024 * 1024 && url.none(Char::isISOControl) &&
            (url.substringBefore(':').lowercase() in setOf("https", "http", "file", "content") || url.startsWith("data:image/", true))
    }

    private fun mergedTable(offset: Int, source: String): MarkdownMergedTable? = runCatching {
        require(source.length <= 512 * 1024)
        val table = org.jsoup.Jsoup.parseBodyFragment(source).select("table").singleOrNull() ?: return null
        val rowElements = table.select("tr").filter { it.parents().firstOrNull { parent -> parent.normalName() == "table" } == table }
        require(rowElements.size in 1..100)
        val occupied = Array(100) { BooleanArray(32) }
        val cells = ArrayList<MarkdownMergedCell>(); var columns = 0
        rowElements.forEachIndexed { row, element ->
            var column = 0
            element.children().filter { it.normalName() == "td" || it.normalName() == "th" }.forEach { cell ->
                while (column < 32 && occupied[row][column]) column++
                require(column < 32 && cells.size < 1000)
                val rowSpan = (cell.attr("rowspan").toIntOrNull() ?: 1).coerceIn(1, rowElements.size - row)
                val colSpan = (cell.attr("colspan").toIntOrNull() ?: 1).coerceIn(1, 32 - column)
                for (r in row until row + rowSpan) for (c in column until column + colSpan) {
                    require(!occupied[r][c]); occupied[r][c] = true
                }
                cells += MarkdownMergedCell(row, column, rowSpan, colSpan, cell.normalName() == "th", cell.wholeText().take(4096))
                column += colSpan; columns = maxOf(columns, column)
            }
        }
        require(columns > 0)
        MarkdownMergedTable(offset, rowElements.size, columns, cells)
    }.getOrNull()

    private fun inline(nodes: List<ASTNode>): AnnotatedString = inlineText(nodes, source, links, style)

    private fun mathSource(node: ASTNode): String {
        val children = node.children
        if (children.size < 2) return node.text()
        return source.substring(children.first().endOffset, children.last().startOffset).trim('\n', ' ')
    }

    private fun ASTNode.text(): String = source.substring(startOffset, endOffset)

    /**
     * 两个连续 EOL 即源码中的空行；出现在列表项之间或列表项内部时，该列表为松散列表。
     * 末尾的空行属于列表之后的内容，不计入。
     */
    private fun ASTNode.hasBlankLine(): Boolean {
        val lastContent = children.indexOfLast { it.type != MarkdownTokenTypes.EOL }
        return (1..lastContent).any { index ->
            children[index - 1].type == MarkdownTokenTypes.EOL && children[index].type == MarkdownTokenTypes.EOL
        }
    }

    private fun ASTNode.isWhitespaceLike(): Boolean =
        type == MarkdownTokenTypes.WHITE_SPACE || type == MarkdownTokenTypes.EOL

    private companion object {
        val FOOTNOTE_DEFINITION = Regex("""\[\^([^\]\s]+)]:\s*(.+)""", RegexOption.DOT_MATCHES_ALL)
        val THEMATIC_BREAK = Regex("""(?m)^\s*(?:\*\s*){3,}$|^\s*(?:-\s*){3,}$|^\s*(?:_\s*){3,}$""")
    }
}

private fun String.parseAlignments(columns: Int): List<MarkdownColumnAlign> {
    val specs = trim().removePrefix("|").removeSuffix("|").split('|').map(String::trim)
    return List(columns) { index ->
        val spec = specs.getOrNull(index).orEmpty()
        when {
            spec.startsWith(':') && spec.endsWith(':') -> MarkdownColumnAlign.Center
            spec.endsWith(':') -> MarkdownColumnAlign.End
            else -> MarkdownColumnAlign.Start
        }
    }
}

private fun String.removeIndent(max: Int): String {
    var index = 0
    while (index < length && index < max && this[index] == ' ') index += 1
    return substring(index)
}

internal fun List<MarkdownBlock>.revealKeys(): Set<RevealBlockKey> = buildSet {
    fun collect(block: MarkdownBlock) {
        when (block) {
            is MarkdownTextBlock -> if (block.text.isNotEmpty()) add(block.revealKey)
            is MarkdownFlow -> block.blocks.forEach(::collect)
            is MarkdownMergedTable -> Unit
            is MarkdownQuote -> block.blocks.forEach(::collect)
            is MarkdownAlert -> block.blocks.forEach(::collect)
            is MarkdownList -> block.items.forEach { item -> item.blocks.forEach(::collect) }
            is MarkdownTable -> (block.header + block.rows.flatten()).forEach(::collect)
            is MarkdownMath, is MarkdownImage -> Unit
            is MarkdownRule -> Unit
        }
    }
    this@revealKeys.forEach(::collect)
}

/** 列表项 marker 的显示时机：项内按源码顺序第一个有文字的块开始显现时。 */
private fun List<MarkdownBlock>.firstRevealKey(): RevealBlockKey? = asSequence()
    .mapNotNull { block ->
        when (block) {
            is MarkdownTextBlock -> block.revealKey.takeIf { block.text.isNotEmpty() }
            is MarkdownFlow -> block.blocks.firstRevealKey()
            is MarkdownMergedTable -> null
            is MarkdownQuote -> block.blocks.firstRevealKey()
            is MarkdownAlert -> block.blocks.firstRevealKey()
            is MarkdownList -> block.items.firstOrNull()?.markerRevealKey
            is MarkdownTable -> block.header.firstOrNull { it.text.isNotEmpty() }?.revealKey
            is MarkdownMath, is MarkdownImage -> null
            is MarkdownRule -> null
        }
    }
    .firstOrNull()
