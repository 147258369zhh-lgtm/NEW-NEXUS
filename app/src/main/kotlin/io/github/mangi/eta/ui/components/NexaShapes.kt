package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * One continuous-curvature shape used by NEXA surfaces and controls.
 *
 * The straight edge hands its tangent into a cubic corner. The radius accelerates
 * gradually instead of joining a half circle to two straight edges, which removes
 * the visible "notches" produced by ordinary round rectangles at small radii.
 */
@Immutable
internal data class NexaSmoothShape(
    val topStart: Dp = 0.dp,
    val topEnd: Dp = 0.dp,
    val bottomEnd: Dp = 0.dp,
    val bottomStart: Dp = 0.dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (size.minDimension <= 0f) return Outline.Rectangle(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
        val cornerLimit = size.minDimension * 0.5f
        val startTop = density.run { topStart.toPx() }.coerceIn(0f, cornerLimit)
        val endTop = density.run { topEnd.toPx() }.coerceIn(0f, cornerLimit)
        val endBottom = density.run { bottomEnd.toPx() }.coerceIn(0f, cornerLimit)
        val startBottom = density.run { bottomStart.toPx() }.coerceIn(0f, cornerLimit)
        val rtl = layoutDirection == LayoutDirection.Rtl
        val topLeft = if (rtl) endTop else startTop
        val topRight = if (rtl) startTop else endTop
        val bottomLeft = if (rtl) endBottom else startBottom
        val bottomRight = if (rtl) startBottom else endBottom
        val path = Path().apply {
            moveTo(topLeft, 0f)
            lineTo(size.width - topRight, 0f)
            cubicCornerTo(size.width - topRight * CURVE, 0f, size.width, topRight * CURVE, size.width, topRight)
            lineTo(size.width, size.height - bottomRight)
            cubicCornerTo(size.width, size.height - bottomRight * CURVE, size.width - bottomRight * CURVE, size.height, size.width - bottomRight, size.height)
            lineTo(bottomLeft, size.height)
            cubicCornerTo(bottomLeft * CURVE, size.height, 0f, size.height - bottomLeft * CURVE, 0f, size.height - bottomLeft)
            lineTo(0f, topLeft)
            cubicCornerTo(0f, topLeft * CURVE, topLeft * CURVE, 0f, topLeft, 0f)
            close()
        }
        return Outline.Generic(path)
    }

    private fun Path.cubicCornerTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        cubicTo(x1, y1, x2, y2, x3, y3)
    }

    private companion object {
        const val CURVE = 0.46f
    }
}

internal fun smoothShape(radius: Dp) = NexaSmoothShape(radius, radius, radius, radius)

internal object NexaShapes {
    val control = smoothShape(18.dp)
    val input = smoothShape(27.dp)
    val button = smoothShape(22.dp)
    val card = smoothShape(24.dp)
    val section = smoothShape(30.dp)
    val overlay = smoothShape(34.dp)
    val pill = smoothShape(999.dp)
}
