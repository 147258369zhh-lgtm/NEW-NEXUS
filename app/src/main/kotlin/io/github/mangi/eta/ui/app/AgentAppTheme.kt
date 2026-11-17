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
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearanceFontFamily
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceSkin
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.ui.components.NexaEditorial
import io.github.mangi.eta.ui.components.NexaHumanist
import io.github.zhh_lgtm.nexus.application.api.PresentationProfileResolver
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
    val controller = remember(colorSchemeMode, keyColor, appearance.paletteStyle, isDark) {
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
        val pureBlack = appearance.pureBlackEnabled && isDark
        // Miuix updates Colors in place; a remembered copy keyed by its identity would
        // retain old dark-theme values after the upstream palette changes.
        val themedColors = run {
            val palette = if (pureBlack) {
                baseColors.copy(
                    background = Color.Black,
                    surface = Color.Black,
                )
            } else {
                baseColors
            }
            val skinPalette = when (appearance.skin) {
                AppearanceSkin.CLASSIC -> if (isDark) palette else palette.copy(
                    // ColorOS 式光学关系：珍珠雾画布承托真正纯白的内容卡。
                    background = Color(0xFFF0F1F3),
                    surface = Color.White,
                    surfaceContainer = Color.White,
                    surfaceContainerHigh = Color(0xFFFAFAFC),
                )
                AppearanceSkin.OMNI, AppearanceSkin.AURORA -> palette
                AppearanceSkin.FROST_GLASS, AppearanceSkin.LIQUID_GLASS -> {
                    val liquid = appearance.skin == AppearanceSkin.LIQUID_GLASS
                    // Large sheets stay near-opaque. Only cards and selected small controls
                    // transmit atmosphere; foreground contrast is never delegated to blur.
                    palette.copy(
                        surfaceContainer = palette.surfaceContainer.copy(alpha = if (liquid) 0.78f else 0.90f),
                        surfaceContainerHigh = palette.surfaceContainerHigh.copy(alpha = if (liquid) 0.88f else 0.96f),
                    )
                }
            }
            if (isDark) {
                skinPalette
            } else {
                // Miuix uses secondaryVariant for ordinary button fills. Keep the pearl
                // canvas and white cards intact, while separating plain actions as brighter
                // controls instead of letting them disappear into the surrounding surface.
                val controlSurface = when (appearance.skin) {
                    AppearanceSkin.FROST_GLASS -> Color.White.copy(alpha = 0.98f)
                    AppearanceSkin.LIQUID_GLASS -> Color.White.copy(alpha = 0.94f)
                    else -> Color.White
                }
                skinPalette.copy(
                    secondaryVariant = controlSurface,
                    disabledSecondaryVariant = controlSurface.copy(alpha = 0.68f),
                )
            }
        }
        val baseTextStyles = MiuixTheme.textStyles
        val themedTextStyles = remember(baseTextStyles, appearance.fontFamily, appearance.skin) {
            baseTextStyles.withFontPreset(appearance.fontFamily).withWeightForSkin(appearance.skin)
        }
        MiuixTheme(colors = themedColors, textStyles = themedTextStyles) {
            val designTokens = remember(
                appearance.skin,
                appearance.glassOpacityMultiplier,
                appearance.glassBlurMultiplier,
                isDark,
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
                    AppearanceSkin.AURORA -> auroraDesignTokens(
                        surface = themedColors.surface,
                        surfaceElevated = themedColors.surfaceContainerHigh,
                        onSurface = themedColors.onSurface,
                        onSurfaceVariant = themedColors.onSurfaceVariantSummary,
                        accent = accent,
                        glassTint = themedColors.surfaceContainer.copy(alpha = 0.82f),
                        glassBorder = themedColors.outline.copy(alpha = 0.30f),
                    )
                    AppearanceSkin.FROST_GLASS, AppearanceSkin.LIQUID_GLASS -> glassDesignTokens(
                        skin = appearance.skin,
                        surface = themedColors.surface,
                        surfaceElevated = themedColors.surfaceContainer,
                        onSurface = themedColors.onSurface,
                        onSurfaceVariant = themedColors.onSurfaceVariantSummary,
                        accent = accent,
                        glassTint = themedColors.surface,
                        glassBorder = themedColors.outline.copy(alpha = if (isDark) 0.40f else 0.24f),
                    ).let { tokens ->
                        val normalized = appearance.normalized()
                        tokens.copy(
                            glassAlpha = (tokens.glassAlpha * normalized.glassOpacityMultiplier).coerceIn(0.25f, 0.95f),
                            blurRadius = (tokens.blurRadius.value * normalized.glassBlurMultiplier).coerceAtMost(32f).dp,
                        )
                    }
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

private fun TextStyles.withFontPreset(preset: AppearanceFontFamily): TextStyles {
    val bodyFamily = when (preset) {
        AppearanceFontFamily.SYSTEM -> FontFamily.Default
        AppearanceFontFamily.HUMANIST -> NexaHumanist
        AppearanceFontFamily.EDITORIAL -> FontFamily.SansSerif
    }
    val displayFamily = if (preset == AppearanceFontFamily.EDITORIAL) NexaEditorial else bodyFamily
    return copy(
        main = main.copy(fontFamily = displayFamily),
        paragraph = paragraph.copy(fontFamily = bodyFamily),
        body1 = body1.copy(fontFamily = bodyFamily),
        body2 = body2.copy(fontFamily = bodyFamily),
        button = button.copy(fontFamily = bodyFamily),
        footnote1 = footnote1.copy(fontFamily = bodyFamily),
        footnote2 = footnote2.copy(fontFamily = bodyFamily),
        headline1 = headline1.copy(fontFamily = displayFamily),
        headline2 = headline2.copy(fontFamily = displayFamily),
        subtitle = subtitle.copy(fontFamily = displayFamily),
        title1 = title1.copy(fontFamily = displayFamily),
        title2 = title2.copy(fontFamily = displayFamily),
        title3 = title3.copy(fontFamily = displayFamily),
        title4 = title4.copy(fontFamily = displayFamily),
    )
}

private fun TextStyles.withWeightForSkin(skin: AppearanceSkin): TextStyles {
    if (skin != AppearanceSkin.AURORA && !skin.isGlass) return this
    return copy(
        main = main.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        paragraph = paragraph.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
        body1 = body1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
        body2 = body2.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
        headline1 = headline1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        headline2 = headline2.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        title1 = title1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
        title2 = title2.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        title3 = title3.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        title4 = title4.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
    )
}
