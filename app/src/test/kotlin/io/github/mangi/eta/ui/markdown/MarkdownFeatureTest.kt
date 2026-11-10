package io.github.mangi.eta.ui.markdown

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownFeatureTest {
    @Test
    fun blockMathBecomesRenderedMathBlock() {
        val document = StreamingGfmParserSession().parse("\$\$x^2 + \\frac{1}{2}\$\$", isComplete = true).document

        val math = document.blocks.filterIsInstance<MarkdownMath>().single()
        assertTrue(math.latex.contains("x^2"))
        assertTrue(LatexToUnicodeFormatter.format(math.latex).contains("x²"))
    }

    @Test
    fun latexEnvironmentAndMalformedFragmentNeverCrashRenderer() {
        val matrix = LatexToUnicodeFormatter.format("""\begin{matrix}a&b\\c&d\end{matrix}""")
        val malformed = LatexToUnicodeFormatter.format("""\begin{bmatrix}x&y""")

        assertTrue(matrix.contains("a"))
        assertTrue(matrix.contains("b"))
        assertFalse(matrix.contains("\\begin"))
        assertTrue(malformed.isNotEmpty())
    }

    @Test
    fun inlineMathNoLongerLeaksDollarDelimiters() {
        val paragraph = StreamingGfmParserSession().parse("结果为 \$x_1 + \\sqrt{9}\$。", isComplete = true)
            .document.blocks.filterIsInstance<MarkdownParagraph>().single()

        assertFalse(paragraph.text.text.contains('$'))
        assertTrue(paragraph.text.text.contains('√'))
        assertTrue(paragraph.text.text.contains('₁'))
    }

    @Test
    fun standaloneImageBecomesImageBlock() {
        val image = StreamingGfmParserSession()
            .parse("![diagram](https://example.com/chart.png)", isComplete = true)
            .document.blocks.filterIsInstance<MarkdownImage>().single()

        assertEquals("diagram", image.alt)
        assertEquals("https://example.com/chart.png", image.url)
    }

    @Test
    fun safeHtmlProducesStylesWithoutLeakingTags() {
        val paragraph = StreamingGfmParserSession().parse("<strong>重要</strong>内容", isComplete = true)
            .document.blocks.filterIsInstance<MarkdownParagraph>().single()

        assertEquals("重要内容", paragraph.text.text)
        assertTrue(paragraph.text.spanStyles.isNotEmpty())
    }

    @Test
    fun codeHighlightingPreservesSource() {
        val source = "fun main() { println(\"ok\") }"
        val highlighted = highlightCode(
            source,
            "kotlin",
            CodeHighlightPalette(Color.Red, Color.Green, Color.Blue, Color.Gray, Color.Yellow, Color.Cyan),
        )

        assertEquals(source, highlighted.text)
        assertTrue(highlighted.spanStyles.isNotEmpty())
    }
}
