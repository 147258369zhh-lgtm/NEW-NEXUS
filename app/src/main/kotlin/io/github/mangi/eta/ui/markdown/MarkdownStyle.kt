package io.github.mangi.eta.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.data.model.MessageFont
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.components.NexaHumanist
import io.github.mangi.eta.ui.components.NexaEditorial
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 正文与思考共用同一套渲染器，只在字号、颜色与留白上区分层级。 */
internal enum class MarkdownTone { Answer, Thinking }

/**
 * 一次渲染所需的全部样式。按主题与语气整体记忆，块级组件只读取不再推导，
 * 避免每个段落各自拼一遍 TextStyle。
 */
@Immutable
internal class MarkdownStyle(
    val tone: MarkdownTone,
    val body: TextStyle,
    private val headings: List<TextStyle>,
    val code: TextStyle,
    val table: TextStyle,
    val textColor: Color,
    val secondaryColor: Color,
    val markerColor: Color,
    val dividerColor: Color,
    val codeBackground: Color,
    val tableHeaderBackground: Color,
    val inline: MarkdownInlineStyle,
    val paragraphGapScale: Float = 1f,
    val codeWrap: Boolean = false,
    val tableDensity: Float = 1f,
) {
    fun heading(level: Int): TextStyle = headings[(level - 1).coerceIn(0, headings.lastIndex)]

    /** 引用块内的弱化样式：文字退到次要色，结构与字号不变。 */
    fun muted(): MarkdownStyle = MarkdownStyle(
        tone = tone,
        body = body.copy(color = secondaryColor),
        headings = headings.map { it.copy(color = secondaryColor) },
        code = code,
        table = table.copy(color = secondaryColor),
        textColor = secondaryColor,
        secondaryColor = secondaryColor,
        markerColor = markerColor,
        dividerColor = dividerColor,
        codeBackground = codeBackground,
        tableHeaderBackground = tableHeaderBackground,
        inline = inline,
        paragraphGapScale = paragraphGapScale,
        codeWrap = codeWrap,
        tableDensity = tableDensity,
    )
}

@Composable
internal fun rememberMarkdownStyle(tone: MarkdownTone): MarkdownStyle {
    val colors = MiuixTheme.colorScheme
    val textStyles = MiuixTheme.textStyles
    val typography = LocalAppearanceSettings.current.messageTypography.normalized()
    return remember(tone, colors, textStyles, typography) {
        val answer = tone == MarkdownTone.Answer
        val textColor = if (answer) colors.onSurface else colors.onSurfaceVariantSummary
        val body = (if (answer) textStyles.body1 else textStyles.body2).copy(
            fontSize = (if (answer) typography.bodySize else typography.thinkingSize).sp,
            lineHeight = (if (answer) typography.lineHeight else typography.thinkingLineHeight).sp,
            fontFamily = messageFontFamily(typography.font) ?: textStyles.body1.fontFamily,
            fontWeight = if (answer && typography.bodyWeight != 0) FontWeight(typography.bodyWeight)
                else (if (answer) textStyles.body1 else textStyles.body2).fontWeight,
            letterSpacing = (if (answer) typography.bodyLetterSpacing else typography.thinkingLetterSpacing)
                .takeUnless { it == 0f }?.sp ?: (if (answer) textStyles.body1 else textStyles.body2).letterSpacing,
            color = textColor,
        )
        // 聊天里的标题只是段落强调，不是页面标题：字号克制，层级主要靠字重区分。
        val headings = if (answer) {
            listOf(
                body.copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
                body.copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
                body.copy(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
                body.copy(fontWeight = FontWeight.SemiBold),
            )
        } else {
            listOf(body.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
        }
        val scaledHeadings = headings.map { heading ->
            val bodyScale = if (answer) typography.bodySize / 16f else typography.thinkingSize / 14f
            heading.copy(
                fontSize = (heading.fontSize.value * typography.headingScale * bodyScale).sp,
                lineHeight = (heading.lineHeight.value * typography.headingScale * bodyScale).sp,
                fontWeight = FontWeight(typography.headingWeight),
            )
        }
        MarkdownStyle(
            tone = tone,
            body = body,
            headings = scaledHeadings,
            code = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = (if (answer) typography.codeSize else (typography.codeSize - 1).coerceAtLeast(10f)).sp,
                lineHeight = (typography.codeSize * typography.codeLineHeightScale).sp,
                color = textColor,
            ),
            table = body.copy(fontSize = typography.tableSize.sp, lineHeight = (typography.tableSize * 1.5f).sp),
            textColor = textColor,
            secondaryColor = colors.onSurfaceVariantSummary,
            markerColor = if (answer) colors.onSurface.copy(alpha = 0.55f) else colors.onSurfaceVariantSummary,
            dividerColor = colors.outline.copy(alpha = 0.6f),
            codeBackground = colors.surfaceContainer,
            tableHeaderBackground = colors.surfaceContainer,
            inline = MarkdownInlineStyle(
                linkColor = colors.primary,
                codeBackground = colors.onSurface.copy(alpha = 0.07f),
            ),
            paragraphGapScale = typography.paragraphGapScale,
            codeWrap = typography.codeWrap,
            tableDensity = typography.tableDensity,
        )
    }
}

/**
 * 相邻块之间的留白。源码中的空行只用于切分块，不直接占据高度；可见间距按语义分配：
 * 段落之间约半行，标题前更宽以开启新段落，标题后收紧使其贴近所属内容。
 */
internal fun markdownBlockGap(previous: MarkdownBlock?, current: MarkdownBlock, tone: MarkdownTone): TextUnit {
    if (previous == null) return 0.sp
    val gap = when {
        current is MarkdownHeading && previous is MarkdownHeading -> 8f
        current is MarkdownHeading -> if (current.level <= 2) 22f else 18f
        previous is MarkdownHeading -> 8f
        previous is MarkdownParagraph && current is MarkdownParagraph -> 12f
        else -> 14f
    }
    val scale = if (tone == MarkdownTone.Answer) 1f else THINKING_GAP_SCALE
    return (gap * scale).sp
}

/** 紧凑列表项内部（例如段落后紧跟子列表）只保留很小的间距。 */
internal fun markdownCompactGap(previous: MarkdownBlock?, tone: MarkdownTone): TextUnit =
    if (previous == null) 0.sp else if (tone == MarkdownTone.Answer) 4.sp else 3.sp

internal const val ANSWER_LINE_HEIGHT_SP = 26
internal const val THINKING_LINE_HEIGHT_SP = 22
private const val THINKING_GAP_SCALE = 0.75f

internal fun messageFontFamily(font: MessageFont): FontFamily? = when (font) {
    MessageFont.FOLLOW_INTERFACE -> null
    MessageFont.SYSTEM -> FontFamily.Default
    MessageFont.HUMANIST -> NexaHumanist
    MessageFont.EDITORIAL -> NexaEditorial
    MessageFont.MONOSPACE -> FontFamily.Monospace
    MessageFont.SYSTEM_SERIF -> FontFamily.Serif
}

@Composable
internal fun messageBodyTextStyle(): TextStyle {
    val typography = LocalAppearanceSettings.current.messageTypography.normalized()
    val base = MiuixTheme.textStyles.body1
    return remember(typography, base) {
        base.copy(fontSize = typography.bodySize.sp, lineHeight = typography.lineHeight.sp,
            fontFamily = messageFontFamily(typography.font) ?: base.fontFamily,
            fontWeight = if (typography.bodyWeight == 0) base.fontWeight else FontWeight(typography.bodyWeight),
            letterSpacing = typography.bodyLetterSpacing.takeUnless { it == 0f }?.sp ?: base.letterSpacing)
    }
}
