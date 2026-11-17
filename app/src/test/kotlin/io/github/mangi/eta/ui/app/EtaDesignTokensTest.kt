package io.github.mangi.eta.ui.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AppearanceSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

private typealias TokenFactory = (
    Color,
    Color,
    Color,
    Color,
    Color,
    Color,
    Color,
) -> EtaDesignTokens

class EtaDesignTokensTest {
    private val colors = listOf(
        Color(0xFF111111),
        Color(0xFF222222),
        Color(0xFFEEEEEE),
        Color(0xFFAAAAAA),
        Color(0xFF3366FF),
        Color(0x113366FF),
        Color(0x553366FF),
    )

    @Test
    fun classicTokensPreserveCurrentGeometryAndDensity() {
        val tokens = tokens(::classicDesignTokens)

        assertEquals(AppearanceSkin.CLASSIC, tokens.skin)
        assertEquals(16.dp, tokens.cornerMedium)
        assertEquals(0.72f, tokens.glassAlpha)
        assertEquals(1f, tokens.densityMultiplier)
        assertEquals(FontWeight.SemiBold, tokens.titleWeight)
    }

    @Test
    fun omniTokensAreDistinctButUseTheSharedThemeColors() {
        val classic = tokens(::classicDesignTokens)
        val omni = tokens(::omniDesignTokens)

        assertEquals(AppearanceSkin.OMNI, omni.skin)
        assertEquals(classic.accent, omni.accent)
        assertEquals(classic.surface, omni.surface)
        assertNotEquals(classic.cornerMedium, omni.cornerMedium)
        assertNotEquals(classic.glassAlpha, omni.glassAlpha)
        assertNotEquals(classic.densityMultiplier, omni.densityMultiplier)
    }

    @Test
    fun auroraTokensUseCompactMaterialInspiredMetrics() {
        val aurora = tokens(::auroraDesignTokens)
        assertEquals(AppearanceSkin.AURORA, aurora.skin)
        assertEquals(8.dp, aurora.cornerSmall)
        assertEquals(12.dp, aurora.cornerMedium)
        assertEquals(0.82f, aurora.glassAlpha)
        assertEquals(0.94f, aurora.densityMultiplier)
    }

    private fun tokens(factory: TokenFactory): EtaDesignTokens =
        factory(colors[0], colors[1], colors[2], colors[3], colors[4], colors[5], colors[6])
}
