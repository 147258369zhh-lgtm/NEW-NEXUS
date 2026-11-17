package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Transparent, audio-reactive overlapping ribbons; the phase is read only during drawing. */
@Composable
internal fun DictationWaveform(
    active: Boolean,
    level: () -> Float,
    samples: () -> FloatArray,
    modifier: Modifier = Modifier,
) {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        if (active) {
            val start = withFrameNanos { it }
            while (isActive) withFrameNanos { phase.floatValue = ((it - start) / 1_000_000_000.0).toFloat() }
        }
    }
    Canvas(modifier) {
        val time = phase.floatValue
        val energy = if (active) level().coerceIn(0f, 1f) else 0f
        val audio = samples()
        val mid = size.height * .48f
        val half = size.width * .47f
        val height = size.height * (.15f + .22f * energy)
        val colors = listOf(Color(0xFF3BDFF1), Color(0xFF84EBFF), Color(0xFF8B9BFF), Color(0xFFDD8EF3))
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFFBDEFFF).copy(alpha = .42f), Color.Transparent)),
            Offset(size.width * .04f, mid), Offset(size.width * .96f, mid), 1.dp.toPx())
        repeat(8) { layer ->
            val upper = Path()
            val lower = Path()
            val fill = Path()
            val points = ArrayList<Offset>(81)
            val tint = colors[layer % colors.size].copy(alpha = if (active) .84f else .45f)
            for (step in 0..80) {
                val t = step / 80f
                val x = size.width / 2f + (t * 2f - 1f) * half
                val envelope = exp(-((t - .5f) * (t - .5f) * 21f)) * sin(PI * t).toFloat()
                val sample = if (audio.isEmpty()) 0f else audio[(t * (audio.size - 1)).toInt()].coerceIn(-1f, 1f)
                val wave = sin(t * PI * (5.4 + layer * .22) - time * (1.3 + layer * .055) + layer * .49).toFloat()
                val y = envelope * (wave * height * (1f - layer * .035f) + sample * height * .14f)
                val top = Offset(x, mid - y)
                points += top
                if (step == 0) { upper.moveTo(x, top.y); lower.moveTo(x, mid + y); fill.moveTo(x, top.y) }
                else { upper.lineTo(x, top.y); lower.lineTo(x, mid + y); fill.lineTo(x, top.y) }
            }
            points.asReversed().forEach { fill.lineTo(it.x, mid * 2 - it.y) }
            fill.close()
            drawPath(fill, Brush.verticalGradient(listOf(tint.copy(alpha = .015f), tint.copy(alpha = .075f), tint.copy(alpha = .015f))))
            listOf(upper, lower).forEach { path ->
                drawPath(path, tint.copy(alpha = .045f), style = Stroke(9.dp.toPx()))
                drawPath(path, tint.copy(alpha = .18f), style = Stroke(3.dp.toPx()))
                drawPath(path, tint, style = Stroke(1.15.dp.toPx()))
            }
        }
    }
}
