package io.github.mangi.eta.agent.voice

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.isActive

/**
 * NEXA voice presence. The classic skin uses a Plasma Orb; liquid glass uses a
 * Waveform Ring driven by the most recent PCM window supplied by speech capture.
 */
@Composable
internal fun NexaVoiceOrb(
    active: Boolean,
    speaking: Boolean,
    level: () -> Float,
    waveform: () -> FloatArray,
    modifier: Modifier = Modifier,
    animate: Boolean = active,
    polished: Boolean = false,
) {
    val tokens = LocalEtaDesignTokens.current
    val liquid = tokens.skin.isGlass
    var elapsed by remember { mutableFloatStateOf(0f) }
    val clock = remember { longArrayOf(0L) }
    LaunchedEffect(active, animate) {
        if (!active || !animate) return@LaunchedEffect
        clock[0] = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (clock[0] == 0L) clock[0] = now
                elapsed = (now - clock[0]) / 1_000_000_000f
            }
        }
    }

    Canvas(modifier) {
        if (!active) return@Canvas
        val liveLevel = level().coerceIn(0f, 1f)
        val samples = waveform()
        if (liquid) {
            drawWaveformRing(elapsed, liveLevel, samples, speaking)
        } else {
            drawPlasmaOrb(elapsed, liveLevel, samples, speaking)
        }
        if (polished) drawOrbFinish(elapsed, liveLevel, liquid)
    }
}

/** Bounded lighting over the existing sphere/ring; no blur or full-window effect. */
private fun DrawScope.drawOrbFinish(time: Float, level: Float, liquid: Boolean) {
    val radius = size.minDimension * if (liquid) 0.26f else 0.285f *
        (1f + 0.035f * sin(time * 3.1f) + level * 0.075f)
    val sphere = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center - Offset(radius, radius),
        androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f))) }
    clipPath(sphere) {
        drawCircle(Brush.radialGradient(
            listOf(Color(0xFF3746AD).copy(alpha = if (liquid) 0.10f else 0.35f), Color.Transparent),
            center + Offset(radius * 0.48f, radius * 0.66f), radius * 1.35f), radius = radius)
        drawCircle(Brush.radialGradient(
            listOf(Color.White.copy(alpha = if (liquid) 0.38f else 0.62f), Color.Transparent),
            center - Offset(radius * 0.38f, radius * 0.49f), radius * 0.76f), radius = radius)
        drawArc(Color.White.copy(alpha = if (liquid) 0.44f else 0.34f), 207f, 87f, false,
            topLeft = center - Offset(radius * 0.92f, radius * 0.92f),
            size = androidx.compose.ui.geometry.Size(radius * 1.84f, radius * 1.84f),
            style = Stroke(width = size.minDimension * 0.008f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.drawWaveformRing(
    time: Float,
    level: Float,
    samples: FloatArray,
    speaking: Boolean,
) {
    val radius = size.minDimension * 0.26f
    val ringRadius = radius * 1.16f
    val energy = (samples.maxOfOrNull { abs(it) } ?: 0f).coerceIn(0f, 1f)
    val motion = if (speaking) 1f else 0.62f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF8FD9FF).copy(alpha = 0.28f + 0.18f * level),
                Color(0xFF4F8DFF).copy(alpha = 0.10f),
                Color.Transparent,
            ),
            center = center,
            radius = size.minDimension * 0.50f,
        ),
        radius = size.minDimension * 0.50f,
    )

    val bars = 96
    val count = samples.size.coerceAtLeast(1)
    repeat(bars) { index ->
        val phase = index.toFloat() / bars
        val sampleIndex = ((phase + time * 0.055f) % 1f * (count - 1)).toInt().coerceIn(0, count - 1)
        val previousIndex = (sampleIndex - 2).coerceAtLeast(0)
        val nextIndex = (sampleIndex + 2).coerceAtMost(count - 1)
        val signal = ((samples[sampleIndex] + samples[previousIndex] + samples[nextIndex]) / 3f).coerceIn(-1f, 1f)
        val shaped = (abs(signal) * 0.70f + energy * 0.30f + level * 0.18f).coerceIn(0f, 1f)
        val angle = phase * (PI * 2.0) - PI / 2.0
        val inner = ringRadius * 1.015f
        val outer = inner + radius * (0.045f + shaped * 0.40f * motion)
        val direction = Offset(cos(angle).toFloat(), sin(angle).toFloat())
        val start = center + direction * inner
        val end = center + direction * outer
        val hue = 0.18f + shaped * 0.80f
        val color = blendVoiceColor(hue, speaking)
        drawLine(
            color = color.copy(alpha = 0.48f + shaped * 0.48f),
            start = start,
            end = end,
            strokeWidth = size.minDimension * (0.0065f + shaped * 0.004f),
            cap = StrokeCap.Round,
        )
    }

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.18f),
                Color(0xFFB6ECFF).copy(alpha = 0.075f),
                Color(0xFF6A7DFF).copy(alpha = 0.055f),
                Color.Transparent,
            ),
            center = center - Offset(radius * 0.22f, radius * 0.25f),
            radius = radius * 1.12f,
        ),
        radius = radius,
        center = center,
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.24f),
        radius = radius,
        center = center,
        style = Stroke(width = size.minDimension * 0.005f),
    )
    rotate(degrees = time * 28f, pivot = center) {
        drawArc(
            brush = Brush.sweepGradient(
                listOf(
                    Color.Transparent,
                    Color(0xFF75E4FF).copy(alpha = 0.78f),
                    Color(0xFFFF78C9).copy(alpha = 0.34f),
                    Color.Transparent,
                ),
            ),
            startAngle = 188f,
            sweepAngle = 122f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
            style = Stroke(width = size.minDimension * 0.009f, cap = StrokeCap.Round),
        )
    }
    drawCircle(
        color = Color.White.copy(alpha = 0.15f + level * 0.16f),
        radius = radius * (0.070f + level * 0.055f),
        center = center - Offset(radius * 0.32f, radius * 0.36f),
    )
}

private fun DrawScope.drawPlasmaOrb(
    time: Float,
    level: Float,
    samples: FloatArray,
    speaking: Boolean,
) {
    val radius = size.minDimension * 0.285f
    val energy = (samples.maxOfOrNull { abs(it) } ?: 0f).coerceIn(0f, 1f)
    val pulse = 1f + 0.035f * sin(time * 3.1f) + level * 0.075f
    val orbRadius = radius * pulse
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF78F6FF).copy(alpha = 0.24f + level * 0.20f),
                Color(0xFF4D75FF).copy(alpha = 0.11f),
                Color.Transparent,
            ),
            center = center,
            radius = orbRadius * 1.75f,
        ),
        radius = orbRadius * 1.75f,
        center = center,
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFFA8FFFF),
                Color(0xFF52D9FF).copy(alpha = 0.94f),
                Color(0xFF526DFF).copy(alpha = 0.92f),
                Color(0xFF7B4DFF).copy(alpha = 0.72f),
            ),
            center = center - Offset(orbRadius * 0.30f, orbRadius * 0.33f),
            radius = orbRadius * 1.20f,
        ),
        radius = orbRadius,
        center = center,
    )

    val sphere = Path().apply {
        addOval(
            androidx.compose.ui.geometry.Rect(
                left = center.x - orbRadius,
                top = center.y - orbRadius,
                right = center.x + orbRadius,
                bottom = center.y + orbRadius,
            ),
        )
    }
    clipPath(sphere) {
        repeat(3) { layer ->
            val phase = time * (0.78f + layer * 0.15f) + layer * 1.9f
            val path = Path()
            val yOffset = (layer - 1) * orbRadius * 0.28f
            for (point in 0..72) {
                val p = point / 72f
                val x = center.x - orbRadius + p * orbRadius * 2f
                val angle = p * PI * (2.2 + layer * 0.35) + phase
                val y = center.y + yOffset + sin(angle).toFloat() * orbRadius * (0.17f + energy * 0.08f)
                if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = if (layer == 1) {
                    Color(0xFFFF8DDE).copy(alpha = 0.42f + level * 0.18f)
                } else {
                    Color.White.copy(alpha = 0.30f)
                },
                style = Stroke(
                    width = size.minDimension * (0.006f + layer * 0.002f),
                    cap = StrokeCap.Round,
                ),
            )
        }
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White.copy(alpha = 0.62f), Color.Transparent),
            center = center - Offset(orbRadius * 0.34f, orbRadius * 0.38f),
            radius = orbRadius * 0.52f,
        ),
        center = center - Offset(orbRadius * 0.34f, orbRadius * 0.38f),
        radius = orbRadius * 0.52f,
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.24f),
        radius = orbRadius,
        center = center,
        style = Stroke(width = size.minDimension * 0.006f),
    )
    if (speaking) {
        drawCircle(
            color = Color(0xFFFF7DD6).copy(alpha = 0.18f + level * 0.18f),
            radius = orbRadius * (1.08f + level * 0.10f),
            center = center,
            style = Stroke(width = size.minDimension * 0.005f),
        )
    }
}

private fun blendVoiceColor(value: Float, speaking: Boolean): Color {
    val start = if (speaking) Color(0xFFFF67C7) else Color(0xFF45D8FF)
    val middle = Color(0xFF548CFF)
    val end = Color(0xFFA66CFF)
    return if (value < 0.5f) lerpColor(start, middle, value * 2f) else lerpColor(middle, end, (value - 0.5f) * 2f)
}

private fun lerpColor(from: Color, to: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * t,
        green = from.green + (to.green - from.green) * t,
        blue = from.blue + (to.blue - from.blue) * t,
        alpha = from.alpha + (to.alpha - from.alpha) * t,
    )
}
