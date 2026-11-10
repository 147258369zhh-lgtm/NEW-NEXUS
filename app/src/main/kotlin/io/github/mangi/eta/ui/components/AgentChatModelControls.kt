package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.formatContextUsage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.RichTooltip
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TooltipAnchorPosition
import top.yukonga.miuix.kmp.basic.TooltipBox
import top.yukonga.miuix.kmp.basic.TooltipDefaults
import top.yukonga.miuix.kmp.basic.rememberTooltipState
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentContextUsageButton(
    usage: AgentContextUsageUi,
    isCompacting: Boolean = false,
    onCompact: () -> Unit = {},
    onConfigure: () -> Unit = {},
    canCompact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val tooltipState = rememberTooltipState(isPersistent = true)
    val progress = usage.progress
    val trackColor = MiuixTheme.colorScheme.secondaryContainer
    val progressBrush = Brush.sweepGradient(listOf(StatusSuccess, StatusWarning, StatusError))
    val pulse by rememberInfiniteTransition(label = "context-breathe").animateFloat(
        initialValue = .92f,
        targetValue = if (isCompacting) 1.08f else .92f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "context-breathe-scale",
    )
    val locale = LocalConfiguration.current.locales[0]
    val summary = formatContextUsage(
        usage = usage,
        noUsageText = stringResource(R.string.context_no_previous_usage),
        noLimitText = stringResource(R.string.context_no_model_limit),
        locale = locale,
    )
    val detail = when {
        usage.contextWindow == null -> summary
        usage.contextTokens == null -> stringResource(R.string.context_usage_after_response)
        else -> stringResource(R.string.context_usage_previous_response, summary)
    }
    val usageDescription = stringResource(
        R.string.context_usage_description,
        summary.replace('\n', ' '),
    )
    val tooltipColors = TooltipDefaults.richTooltipColors()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            RichTooltip(
                title = {
                    Text(
                        text = stringResource(R.string.ui_contextual_usage_d12810),
                        color = tooltipColors.titleContentColor,
                        style = MiuixTheme.textStyles.subtitle,
                    )
                },
                action = if (canCompact) {
                    {
                        TextButton(
                            text = stringResource(R.string.context_compact_action),
                            onClick = {
                                onCompact()
                                tooltipState.dismiss()
                            },
                            minWidth = 0.dp,
                            minHeight = 34.dp,
                            cornerRadius = 17.dp,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    null
                },
                colors = tooltipColors,
            ) {
                Text(
                    text = detail,
                    color = tooltipColors.contentColor,
                    style = MiuixTheme.textStyles.body2,
                )
            }
        },
        state = tooltipState,
        focusable = true,
        modifier = modifier,
    ) {
        IconButton(
            onClick = { scope.launch { tooltipState.show() } },
            minWidth = ChatInputActionSize,
            minHeight = ChatInputActionSize,
            modifier = Modifier.pointerInput(onConfigure) {
                detectTapGestures(onLongPress = { onConfigure() })
            },
        ) {
            Canvas(
                modifier = Modifier
                    .size(ChatInputActionIconSize)
                    .scale(if (isCompacting) pulse else 1f)
                    .semantics { contentDescription = usageDescription },
            ) {
                val stroke = 2.5.dp.toPx()
                drawCircle(
                    color = trackColor,
                    radius = (size.minDimension - stroke) / 2f,
                    style = Stroke(width = stroke),
                )
                val current = progress
                if (current != null && current > 0f) {
                    drawArc(
                        brush = progressBrush,
                        startAngle = -90f,
                        sweepAngle = 360f * current.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = androidx.compose.ui.geometry.Size(
                            size.width - stroke,
                            size.height - stroke,
                        ),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}
