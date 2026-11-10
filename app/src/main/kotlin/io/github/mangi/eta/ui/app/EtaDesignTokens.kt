package io.github.mangi.eta.ui.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AppearanceSkin

/** Shared visual constants consumed by screens that can support both Eta skins. */
@Immutable
data class EtaDesignTokens(
    val skin: AppearanceSkin,
    val surface: Color,
    val surfaceElevated: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val accent: Color,
    val glassTint: Color,
    val glassBorder: Color,
    val cornerSmall: Dp,
    val cornerMedium: Dp,
    val cornerLarge: Dp,
    val floatingCorner: Dp,
    val glassAlpha: Float,
    val blurRadius: Dp,
    val densityMultiplier: Float,
    val contentSpacing: Dp,
    val sectionSpacing: Dp,
    val titleWeight: FontWeight,
    val bodyWeight: FontWeight,
    // 从当前聊天、设置和状态组件提取；保持默认布局尺寸，皮肤继续控制形状与字重。
    val settingsHorizontalInset: Dp = 12.dp,
    val chatHorizontalInset: Dp = 20.dp,
    val cardContentPadding: Dp = 16.dp,
    val stateContentPadding: Dp = 24.dp,
    val stateItemSpacing: Dp = 8.dp,
    val minimumTouchTarget: Dp = 48.dp,
    val dialogActionSpacing: Dp = 20.dp,
)

internal val LocalEtaDesignTokens = staticCompositionLocalOf {
    classicDesignTokens(
        surface = Color.Unspecified,
        surfaceElevated = Color.Unspecified,
        onSurface = Color.Unspecified,
        onSurfaceVariant = Color.Unspecified,
        accent = Color.Unspecified,
        glassTint = Color.Unspecified,
        glassBorder = Color.Unspecified,
    )
}

internal fun classicDesignTokens(
    surface: Color,
    surfaceElevated: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
    accent: Color,
    glassTint: Color,
    glassBorder: Color,
): EtaDesignTokens = EtaDesignTokens(
    skin = AppearanceSkin.CLASSIC,
    surface = surface,
    surfaceElevated = surfaceElevated,
    onSurface = onSurface,
    onSurfaceVariant = onSurfaceVariant,
    accent = accent,
    glassTint = glassTint,
    glassBorder = glassBorder,
    cornerSmall = 12.dp,
    cornerMedium = 16.dp,
    cornerLarge = 24.dp,
    floatingCorner = 28.dp,
    glassAlpha = 0.72f,
    blurRadius = 24.dp,
    densityMultiplier = 1f,
    contentSpacing = 12.dp,
    sectionSpacing = 20.dp,
    titleWeight = FontWeight.SemiBold,
    bodyWeight = FontWeight.Normal,
)

internal fun omniDesignTokens(
    surface: Color,
    surfaceElevated: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
    accent: Color,
    glassTint: Color,
    glassBorder: Color,
): EtaDesignTokens = EtaDesignTokens(
    skin = AppearanceSkin.OMNI,
    surface = surface,
    surfaceElevated = surfaceElevated,
    onSurface = onSurface,
    onSurfaceVariant = onSurfaceVariant,
    accent = accent,
    glassTint = glassTint,
    glassBorder = glassBorder,
    cornerSmall = 10.dp,
    cornerMedium = 14.dp,
    cornerLarge = 22.dp,
    floatingCorner = 32.dp,
    glassAlpha = 0.58f,
    blurRadius = 32.dp,
    densityMultiplier = 0.96f,
    contentSpacing = 10.dp,
    sectionSpacing = 16.dp,
    titleWeight = FontWeight.Bold,
    bodyWeight = FontWeight.Medium,
)
