package io.github.mangi.eta.ui.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearanceFontFamily
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceSkin
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.zhh_lgtm.nexus.application.api.PresentationProfileResolver
import io.github.mangi.eta.ui.components.EtaMonospace
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.platformDynamicColors

@Composable
fun AgentAppTheme(
    appearance: AppearanceSettings,
    applyInterfaceScale: Boolean,
    onResolvedDarkModeChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (appearance.themeMode) {
        AppearanceThemeMode.SYSTEM -> systemDark
        AppearanceThemeMode.LIGHT -> false
        AppearanceThemeMode.DARK -> true
    }
    val colorSchemeMode = when {
        !appearance.monetEnabled && appearance.themeMode == AppearanceThemeMode.LIGHT -> ColorSchemeMode.Light
        !appearance.monetEnabled && appearance.themeMode == AppearanceThemeMode.DARK -> ColorSchemeMode.Dark
        !appearance.monetEnabled -> ColorSchemeMode.System
        appearance.themeMode == AppearanceThemeMode.LIGHT -> ColorSchemeMode.MonetLight
        appearance.themeMode == AppearanceThemeMode.DARK -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.MonetSystem
    }
    val systemSeedColor = if (
        appearance.monetEnabled &&
        appearance.accentColor == AppearanceAccentColor.SYSTEM &&
        appearance.paletteStyle != AppearancePaletteStyle.TONAL_SPOT
    ) {
        platformDynamicColors(isDark).primary
    } else {
        null
    }
    val keyColor = when {
        !appearance.monetEnabled -> null
        appearance.accentColor == AppearanceAccentColor.SYSTEM -> systemSeedColor
        else -> appearance.accentColor.seedColor()
    }
    val controller = remember(appearance, colorSchemeMode, keyColor, isDark) {
        ThemeController(
            colorSchemeMode = colorSchemeMode,
            keyColor = keyColor,
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = appearance.paletteStyle.toMiuixPaletteStyle(),
            isDark = isDark,
        )
    }

    LaunchedEffect(isDark) { onResolvedDarkModeChange(isDark) }

    MiuixTheme(controller = controller) {
        val baseColors = MiuixTheme.colorScheme
        val pureBlack = appearance.monetEnabled && appearance.pureBlackEnabled && isDark
        val themedColors = remember(baseColors, pureBlack) {
            if (pureBlack) {
                baseColors.copy(
                    background = Color.Black,
                    surface = Color.Black,
                )
            } else {
                baseColors
            }
        }
        val baseTextStyles = MiuixTheme.textStyles
        val themedTextStyles = remember(baseTextStyles, appearance.fontFamily) {
            baseTextStyles.withFontFamily(appearance.fontFamily.fontFamily())
        }
        MiuixTheme(colors = themedColors, textStyles = themedTextStyles) {
            val designTokens = remember(
                appearance.skin,
                themedColors.primary,
                themedColors.surface,
                themedColors.surfaceContainer,
                themedColors.onSurface,
                themedColors.onSurfaceVariantSummary,
                themedColors.outline,
            ) {
                val accent = themedColors.primary
                when (appearance.skin) {
                    AppearanceSkin.CLASSIC -> classicDesignTokens(
                        surface = themedColors.surface,
                        surfaceElevated = themedColors.surfaceContainer,
                        onSurface = themedColors.onSurface,
                        onSurfaceVariant = themedColors.onSurfaceVariantSummary,
                        accent = accent,
                        glassTint = themedColors.surface.copy(alpha = 0.72f),
                        glassBorder = themedColors.outline.copy(alpha = 0.36f),
                    )
                    AppearanceSkin.OMNI -> omniDesignTokens(
                        surface = themedColors.surface,
                        surfaceElevated = themedColors.surfaceContainer,
                        onSurface = themedColors.onSurface,
                        onSurfaceVariant = themedColors.onSurfaceVariantSummary,
                        accent = accent,
                        glassTint = accent.copy(alpha = 0.10f),
                        glassBorder = accent.copy(alpha = 0.24f),
                    )
                }
            }
            val platformDensity = LocalDensity.current
            val appDensity = remember(platformDensity, appearance.interfaceScale, applyInterfaceScale) {
                if (applyInterfaceScale) {
                    Density(
                        density = platformDensity.density * appearance.interfaceScale,
                        fontScale = platformDensity.fontScale,
                    )
                } else {
                    platformDensity
                }
            }
            val presentationProfile = remember(appearance.skin) {
                PresentationProfileResolver.forSkin(appearance.skin.persistedValue)
            }
            CompositionLocalProvider(
                LocalContentColor provides themedColors.onBackground,
                LocalAppearanceSettings provides appearance,
                LocalEtaDesignTokens provides designTokens,
                LocalBlurEnabled provides appearance.blurEnabled,
                LocalTopBarBlurStyle provides appearance.topBarBlurStyle,
                LocalPlatformDensity provides platformDensity,
                LocalDensity provides appDensity,
                LocalNexusPresentationProfile provides presentationProfile,
            ) {
                content()
            }
        }
    }
}

private fun AppearancePaletteStyle.toMiuixPaletteStyle(): ThemePaletteStyle = when (this) {
    AppearancePaletteStyle.TONAL_SPOT -> ThemePaletteStyle.TonalSpot
    AppearancePaletteStyle.NEUTRAL -> ThemePaletteStyle.Neutral
    AppearancePaletteStyle.VIBRANT -> ThemePaletteStyle.Vibrant
    AppearancePaletteStyle.EXPRESSIVE -> ThemePaletteStyle.Expressive
    AppearancePaletteStyle.RAINBOW -> ThemePaletteStyle.Rainbow
    AppearancePaletteStyle.FRUIT_SALAD -> ThemePaletteStyle.FruitSalad
    AppearancePaletteStyle.MONOCHROME -> ThemePaletteStyle.Monochrome
    AppearancePaletteStyle.FIDELITY -> ThemePaletteStyle.Fidelity
    AppearancePaletteStyle.CONTENT -> ThemePaletteStyle.Content
}

private fun AppearanceAccentColor.seedColor(): Color = when (this) {
    AppearanceAccentColor.SYSTEM, AppearanceAccentColor.BLUE -> Color(0xFF4285F4)
    AppearanceAccentColor.PURPLE -> Color(0xFF7C4DFF)
    AppearanceAccentColor.PINK -> Color(0xFFE91E63)
    AppearanceAccentColor.RED -> Color(0xFFEA4335)
    AppearanceAccentColor.ORANGE -> Color(0xFFFF5722)
    AppearanceAccentColor.YELLOW -> Color(0xFFFBBC04)
    AppearanceAccentColor.GREEN -> Color(0xFF34A853)
    AppearanceAccentColor.TEAL -> Color(0xFF00BCD4)
}

private fun AppearanceFontFamily.fontFamily(): FontFamily = when (this) {
    AppearanceFontFamily.SYSTEM -> FontFamily.Default
    AppearanceFontFamily.SANS_SERIF -> FontFamily.SansSerif
    AppearanceFontFamily.SERIF -> FontFamily.Serif
    AppearanceFontFamily.MONOSPACE -> FontFamily.Monospace
    AppearanceFontFamily.JETBRAINS_MONO -> EtaMonospace
}

private fun TextStyles.withFontFamily(family: FontFamily): TextStyles = copy(
    main = main.copy(fontFamily = family),
    paragraph = paragraph.copy(fontFamily = family),
    body1 = body1.copy(fontFamily = family),
    body2 = body2.copy(fontFamily = family),
    button = button.copy(fontFamily = family),
    footnote1 = footnote1.copy(fontFamily = family),
    footnote2 = footnote2.copy(fontFamily = family),
    headline1 = headline1.copy(fontFamily = family),
    headline2 = headline2.copy(fontFamily = family),
    subtitle = subtitle.copy(fontFamily = family),
    title1 = title1.copy(fontFamily = family),
    title2 = title2.copy(fontFamily = family),
    title3 = title3.copy(fontFamily = family),
    title4 = title4.copy(fontFamily = family),
)
