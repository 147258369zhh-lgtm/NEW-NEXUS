package io.github.mangi.eta.ui.app

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AppearanceSkin

/** Shared visual constants; business state is independent of the selected skin. */
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
    cornerSmall = 16.dp,
    cornerMedium = 24.dp,
    cornerLarge = 30.dp,
    floatingCorner = 34.dp,
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

/** Compact Material-inspired typography and surface metrics; the palette remains Miuix-owned. */
internal fun auroraDesignTokens(
    surface: Color,
    surfaceElevated: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
    accent: Color,
    glassTint: Color,
    glassBorder: Color,
): EtaDesignTokens = EtaDesignTokens(
    skin = AppearanceSkin.AURORA,
    surface = surface,
    surfaceElevated = surfaceElevated,
    onSurface = onSurface,
    onSurfaceVariant = onSurfaceVariant,
    accent = accent,
    glassTint = glassTint,
    glassBorder = glassBorder,
    cornerSmall = 8.dp,
    cornerMedium = 12.dp,
    cornerLarge = 18.dp,
    floatingCorner = 20.dp,
    glassAlpha = 0.82f,
    blurRadius = 16.dp,
    densityMultiplier = 0.94f,
    contentSpacing = 8.dp,
    sectionSpacing = 14.dp,
    titleWeight = FontWeight.SemiBold,
    bodyWeight = FontWeight.Normal,
    settingsHorizontalInset = 12.dp,
    chatHorizontalInset = 16.dp,
    cardContentPadding = 12.dp,
    stateContentPadding = 20.dp,
    stateItemSpacing = 8.dp,
    minimumTouchTarget = 48.dp,
    dialogActionSpacing = 8.dp,
)

/** Glass changes material and geometry without scaling text or shrinking touch targets. */
internal fun glassDesignTokens(
    skin: AppearanceSkin,
    surface: Color,
    surfaceElevated: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
    accent: Color,
    glassTint: Color,
    glassBorder: Color,
): EtaDesignTokens {
    require(skin.isGlass)
    val liquid = skin == AppearanceSkin.LIQUID_GLASS
    return EtaDesignTokens(
        skin = skin,
        surface = surface,
        surfaceElevated = surfaceElevated,
        onSurface = onSurface,
        onSurfaceVariant = onSurfaceVariant,
        accent = accent,
        glassTint = glassTint,
        glassBorder = glassBorder,
        cornerSmall = 16.dp,
        cornerMedium = 24.dp,
        cornerLarge = 30.dp,
        floatingCorner = 34.dp,
        glassAlpha = if (liquid) 0.62f else 0.82f,
        blurRadius = if (liquid) 10.dp else 24.dp,
        densityMultiplier = 1f,
        contentSpacing = 12.dp,
        sectionSpacing = 20.dp,
        titleWeight = FontWeight.SemiBold,
        bodyWeight = FontWeight.Normal,
        chatHorizontalInset = 16.dp,
        dialogActionSpacing = 8.dp,
    )
}
