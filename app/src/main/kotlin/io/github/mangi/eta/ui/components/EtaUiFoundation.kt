package io.github.mangi.eta.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.data.model.AppearanceSkin
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared spacing and motion values for the current UI refinement pass. */
internal object EtaUiMetrics {
    val pageHorizontal = 12.dp
    val cardPadding = 16.dp
    val groupGap = 12.dp
    val cardGap = 8.dp
    val rowMinHeight = 56.dp
    val iconTile = 32.dp
    val iconGlyph = 18.dp
    val iconCorner = 10.dp
    val statusMinHeight = 28.dp
}

internal object EtaTypography {
    val sectionSize = 13.sp
    val sectionLineHeight = 18.sp
    val rowTitleSize = 16.sp
    val rowTitleLineHeight = 22.sp
    val valueSize = 14.sp
    val valueLineHeight = 20.sp
    val statusSize = 12.sp
    val statusLineHeight = 16.sp
    val supportingSize = 12.sp
    val supportingLineHeight = 18.sp
    val metricValueSize = 24.sp
    val metricValueLineHeight = 30.sp
}

internal object EtaMotion {
    const val press = 100
    const val state = 180
    const val content = 220
    const val card = 260
    const val chart = 420

    fun <T> standard(duration: Int = state) = tween<T>(
        durationMillis = duration,
        easing = FastOutSlowInEasing,
    )

    fun <T> pressSpring() = spring<T>(
        dampingRatio = 0.82f,
        stiffness = Spring.StiffnessMedium,
    )

    fun <T> layoutSpring() = spring<T>(
        dampingRatio = 0.86f,
        stiffness = Spring.StiffnessMediumLow,
    )
}

internal enum class EtaIconTone(val accent: Color) {
    MODEL(Color(0xFF3C8CFF)),
    MEMORY(Color(0xFFFFB13B)),
    AUTOMATION(Color(0xFF8B6CFF)),
    TOOL(Color(0xFF24BFD4)),
    SYSTEM(Color(0xFF27C47B)),
    DATA(Color(0xFFFF60A8)),
    RISK(Color(0xFFFF5C72)),
}

@Composable
internal fun EtaIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: EtaIconTone = EtaIconTone.MODEL,
    accentColor: Color? = null,
    enabled: Boolean = true,
    size: Dp = EtaUiMetrics.iconTile,
    glyphSize: Dp = EtaUiMetrics.iconGlyph,
) {
    val accent = accentColor ?: tone.accent
    val tint = if (enabled) accent else MiuixTheme.colorScheme.disabledOnSecondaryVariant
    if (LocalNexusSettingsVisuals.current) {
        Icon(icon, contentDescription = null,
            tint = tint,
            modifier = modifier.size(24.dp))
        return
    }
    val glow = if (enabled) accent.copy(alpha = 0.12f) else Color.Transparent
    Box(
        modifier = modifier
            .size(size)
            .drawWithCache {
                val glowBrush = Brush.radialGradient(
                        colors = listOf(glow, Color.Transparent),
                        center = Offset(this.size.width / 2f, this.size.height / 2f),
                        radius = this.size.maxDimension * 0.72f,
                    )
                onDrawBehind {
                    if (enabled) drawCircle(
                        brush = glowBrush,
                        radius = this.size.maxDimension * 0.72f,
                        center = center,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // 彩色裸图标：不再绘制方块/圆底；只用极淡径向光保持层次。
        Icon(
            icon,
            contentDescription = null,
            tint = tint.copy(alpha = if (enabled) 0.18f else 0f),
            modifier = Modifier
                .size(glyphSize)
                .graphicsLayer { translationY = 0.8.dp.toPx() },
        )
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(glyphSize))
    }
}

internal enum class EtaStatusTone { Neutral, Positive, Warning, Danger }

@Composable
internal fun EtaStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: EtaStatusTone = EtaStatusTone.Neutral,
) {
    val color = when (tone) {
        EtaStatusTone.Neutral -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        EtaStatusTone.Positive -> EtaIconTone.SYSTEM.accent
        EtaStatusTone.Warning -> EtaIconTone.MEMORY.accent
        EtaStatusTone.Danger -> MiuixTheme.colorScheme.error
    }
    val container by animateColorAsState(color.copy(alpha = 0.14f), tween(EtaMotion.state, easing = FastOutSlowInEasing), label = "eta-status-container")
    val border by animateColorAsState(color.copy(alpha = 0.18f), tween(EtaMotion.state, easing = FastOutSlowInEasing), label = "eta-status-border")
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = EtaUiMetrics.statusMinHeight)
            .clip(RoundedCornerShape(50))
            .background(
                Brush.linearGradient(
                    listOf(container, Color.White.copy(alpha = 0.045f), container.copy(alpha = container.alpha * 0.62f)),
                ),
            )
            .border(0.7.dp, border, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 5.dp)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
            Crossfade(
                targetState = text,
                animationSpec = EtaMotion.standard(),
                label = "eta-status-content",
            ) { current ->
                Text(
                    text = current,
                    fontSize = EtaTypography.statusSize,
                    lineHeight = EtaTypography.statusLineHeight,
                    fontWeight = FontWeight.Medium,
                    color = color,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun EtaInteractiveRow(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = EtaUiMetrics.rowMinHeight)
            .etaPressScale(
                interactionSource = interactionSource,
                enabled = enabled,
                pressedScale = 0.982f,
                label = "eta-row-press",
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** Drives press scale inside the graphics layer so animation frames do not recompose the row. */
@Composable
internal fun Modifier.etaPressScale(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    pressedScale: Float,
    label: String,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val feedbackEnabled = LocalAppearanceSettings.current.pressFeedbackEnabled
    val scale = remember(interactionSource) { Animatable(1f) }
    LaunchedEffect(enabled, pressed, pressedScale, label, feedbackEnabled) {
        if (!feedbackEnabled) {
            scale.snapTo(1f)
        } else {
            scale.animateTo(
                targetValue = if (enabled && pressed) pressedScale else 1f,
                animationSpec = EtaMotion.pressSpring(),
            )
        }
    }
    return graphicsLayer {
        scaleX = if (feedbackEnabled) scale.value else 1f
        scaleY = if (feedbackEnabled) scale.value else 1f
    }
}

@Composable
internal fun rememberEtaInteractionSource(): MutableInteractionSource = remember { MutableInteractionSource() }

@Composable
internal fun etaRippleIndication(): androidx.compose.foundation.Indication = LocalIndication.current

/**
 * Shared card finish: one restrained rim, a top specular sheen and a small theme-tinted shadow.
 * The effect stays below text and remains legible in both light and dark themes.
 */
@Composable
internal fun Modifier.etaGlassSurface(
    accent: Color = MiuixTheme.colorScheme.primary,
    cornerRadius: Dp = 18.dp,
): Modifier {
    if (LocalNexusSettingsVisuals.current) return this
    val colors = MiuixTheme.colorScheme
    val tokens = LocalEtaDesignTokens.current
    val glass = tokens.skin.isGlass
    val liquid = tokens.skin == AppearanceSkin.LIQUID_GLASS
    val shape = RoundedCornerShape(cornerRadius)
    val dark = colors.surface.luminance() < 0.42f
    val highlight = Color.White.copy(alpha = when {
        liquid -> if (dark) 0.36f else 0.88f
        glass -> if (dark) 0.20f else 0.68f
        else -> if (dark) 0.075f else 0.34f
    })
    return this
        .shadow(
            elevation = if (glass) 6.dp else if (dark) 7.dp else 4.dp,
            shape = shape,
            ambientColor = accent.copy(alpha = if (dark) 0.24f else 0.12f),
            spotColor = accent.copy(alpha = if (dark) 0.30f else 0.15f),
            clip = false,
        )
        .border(
            width = if (liquid) 1.dp else 0.8.dp,
            brush = Brush.linearGradient(
                listOf(
                    highlight,
                    accent.copy(alpha = if (dark) 0.18f else 0.10f),
                    Color.White.copy(alpha = if (dark) 0.035f else 0.16f),
                    colors.dividerLine.copy(alpha = 0.22f),
                ),
            ),
            shape = shape,
        )
        .drawWithCache {
            val fill = Brush.verticalGradient(listOf(
                colors.surface.copy(alpha = if (liquid) 0.32f else 0.52f),
                accent.copy(alpha = if (dark) 0.045f else 0.025f),
                colors.surface.copy(alpha = 0.20f),
            ))
            val outerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.toPx())
            val inset = 0.8.dp.toPx()
            val innerRadius = androidx.compose.ui.geometry.CornerRadius((cornerRadius.toPx() - inset).coerceAtLeast(0f))
            val innerSize = Size((size.width - inset * 2f).coerceAtLeast(0f), (size.height - inset * 2f).coerceAtLeast(0f))
            val innerStroke = Stroke(width = 0.6.dp.toPx())
            onDrawWithContent {
            // Static material on ordinary cards: no extra backdrop recording or GPU filter pass.
            if (glass) drawRoundRect(
                brush = fill,
                cornerRadius = outerRadius,
            )
            drawContent()
            drawRoundRect(
                color = Color.White.copy(alpha = if (glass) {
                    if (dark) 0.10f else 0.34f
                } else if (dark) 0.035f else 0.13f),
                topLeft = Offset(inset, inset),
                size = innerSize,
                cornerRadius = innerRadius,
                style = innerStroke,
            )
            }
        }
}

/** Quiet page atmosphere that sits behind cards without reducing text contrast. */
@Composable
internal fun Modifier.etaPageAtmosphere(): Modifier {
    val colors = MiuixTheme.colorScheme
    if (LocalNexusSettingsVisuals.current) return background(colors.background)
    val glass = LocalEtaDesignTokens.current.skin.isGlass
    val appearance = LocalAppearanceSettings.current
    val dark = colors.surface.luminance() < 0.42f
    val primary = colors.primary
    val secondary = colors.secondary
    return drawWithCache {
        val tint = Brush.linearGradient(
            listOf(primary.copy(alpha = if (glass) { if (dark) 0.13f else 0.07f } else { if (dark) 0.035f else 0.018f }),
                Color.Transparent, secondary.copy(alpha = if (glass) { if (dark) 0.10f else 0.06f } else { if (dark) 0.025f else 0.014f })),
            start = Offset.Zero, end = Offset(size.width, size.height),
        )
        val base = Brush.verticalGradient(listOf(colors.surface, colors.surfaceContainer.copy(alpha = if (dark) 0.30f else 0.55f), colors.surface))
        val firstCenter = Offset(size.width * (if (glass) 0.90f else 0.88f), size.height * (if (glass) 0.12f else 0.04f))
        val firstRadius = size.minDimension * (if (glass) 0.90f else 0.72f)
        val firstGlow = Brush.radialGradient(listOf(primary.copy(alpha = if (glass) { if (dark) 0.20f else 0.16f } else { if (dark) 0.13f else 0.075f }), Color.Transparent), center = firstCenter, radius = firstRadius)
        val secondCenter = Offset(size.width * (if (glass) 0.05f else 0.08f), size.height * (if (glass) 0.85f else 0.88f))
        val secondRadius = size.minDimension * (if (glass) 0.80f else 0.56f)
        val secondGlow = Brush.radialGradient(listOf(secondary.copy(alpha = if (glass) { if (dark) 0.15f else 0.12f } else { if (dark) 0.08f else 0.045f }), Color.Transparent), center = secondCenter, radius = secondRadius)
        val canvasSize = size
        val dots = buildList {
            if (!glass) {
                val step = 30.dp.toPx()
                var x = step / 2
                while (x < canvasSize.width) {
                    var y = step / 2
                    while (y < canvasSize.height) { add(Offset(x, y)); y += step }
                    x += step
                }
            }
        }
        val dot = Color.White.copy(alpha = if (dark) 0.014f else 0.09f)
        val dotDiameter = 0.76.dp.toPx()
        onDrawWithContent {
        if (glass) {
            drawRect(colors.surface)
            if (!(dark && appearance.monetEnabled && appearance.pureBlackEnabled)) {
                drawRect(tint)
                drawCircle(firstGlow, radius = firstRadius, center = firstCenter)
                drawCircle(secondGlow, radius = secondRadius, center = secondCenter)
            }
        } else {
            drawRect(base)
            drawRect(tint)
            drawCircle(firstGlow, radius = firstRadius, center = firstCenter)
            drawCircle(secondGlow, radius = secondRadius, center = secondCenter)
            drawPoints(dots, PointMode.Points, dot, strokeWidth = dotDiameter, cap = StrokeCap.Round)
        }
        drawContent()
        }
    }
}
