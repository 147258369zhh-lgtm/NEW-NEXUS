package io.github.mangi.eta.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A selector that expands in the current dialog window.
 *
 * Miuix's overlay dropdowns create a second window. When used inside [WindowDialog], that window
 * can be painted behind the parent dialog on Android 16/17. Keeping options in the composition
 * guarantees the correct layer order and also preserves the dialog's navigation and accessibility.
 */
@Composable
fun DialogChoicePreference(
    title: String,
    value: String,
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelectedIndexChange: (Int) -> Unit,
) {
    var expanded by rememberSaveable(title, enabled) { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }
    val currentValueDescription = stringResource(R.string.dialog_choice_current_value, title, value)
    val safeIndex = selectedIndex.takeIf { options.isNotEmpty() }?.coerceIn(options.indices)
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val triggerInteraction = remember { MutableInteractionSource() }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = EtaMotion.layoutSpring(),
        label = "dialog-choice-chevron",
    )

    LaunchedEffect(expanded) {
        if (expanded) {
            // Wait for the expand animation to claim its height before asking the parent dialog
            // scroll container to reveal the option panel.
            delay(190)
            bringIntoViewRequester.bringIntoView()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .etaPressScale(
                    interactionSource = triggerInteraction,
                    enabled = enabled,
                    pressedScale = 0.993f,
                    label = "dialog-choice-trigger-scale",
                )
                .clickable(
                    enabled = enabled,
                    interactionSource = triggerInteraction,
                    indication = etaRippleIndication(),
                ) { expanded = !expanded }
                .semantics { contentDescription = currentValueDescription }
                .padding(start = EtaUiMetrics.cardPadding, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body2.copy(fontSize = 15.sp, lineHeight = 21.sp),
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = value,
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.dialog_choice_collapse else R.string.dialog_choice_expand,
                ),
                modifier = Modifier.padding(start = 6.dp).size(18.dp).rotate(arrowRotation),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = EtaUiMetrics.cardPadding)
                .height(1.dp)
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.08f)),
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = tween(EtaMotion.state, easing = FastOutSlowInEasing),
                expandFrom = Alignment.Top,
            ) + fadeIn(tween(EtaMotion.state)),
            exit = shrinkVertically(
                animationSpec = tween(EtaMotion.state, easing = FastOutSlowInEasing),
                shrinkTowards = Alignment.Top,
            ) + fadeOut(tween(EtaMotion.state)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .etaGlassSurface(accent = MiuixTheme.colorScheme.primary, cornerRadius = 12.dp)
                    .squircleSurface(MiuixTheme.colorScheme.surfaceContainerHigh, 12.dp)
                    .padding(6.dp)
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                if (options.isEmpty()) {
                    Text(
                        text = stringResource(R.string.dialog_choice_empty),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                } else {
                    options.forEachIndexed { index, option ->
                        val selected = index == safeIndex
                        val container by animateColorAsState(
                            targetValue = if (selected) {
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)
                            } else {
                                Color.Transparent
                            },
                            animationSpec = tween(EtaMotion.state, easing = FastOutSlowInEasing),
                            label = "dialog-choice-option",
                        )
                        val optionInteraction = remember(option) { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .etaPressScale(
                                    interactionSource = optionInteraction,
                                    enabled = enabled,
                                    pressedScale = 0.985f,
                                    label = "dialog-choice-option-scale",
                                )
                                .clip(RoundedCornerShape(10.dp))
                                .background(container)
                                .then(
                                    if (selected) Modifier.background(
                                        Brush.linearGradient(
                                            listOf(Color.White.copy(alpha = 0.09f), Color.Transparent),
                                        ),
                                    ) else Modifier,
                                )
                                .selectable(
                                    selected = selected,
                                    enabled = enabled,
                                    role = Role.RadioButton,
                                    interactionSource = optionInteraction,
                                    indication = etaRippleIndication(),
                                    onClick = {
                                        onSelectedIndexChange(index)
                                        expanded = false
                                    },
                                )
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = option,
                                modifier = Modifier.weight(1f),
                                style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 20.sp),
                                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                            )
                            AnimatedVisibility(
                                visible = selected,
                                enter = fadeIn(EtaMotion.standard()) + scaleIn(
                                    initialScale = 0.72f,
                                    animationSpec = EtaMotion.layoutSpring(),
                                ),
                                exit = fadeOut(EtaMotion.standard(EtaMotion.press)) + scaleOut(targetScale = 0.72f),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .squircleSurface(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f), 10.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = stringResource(R.string.dialog_choice_selected),
                                        modifier = Modifier.size(14.dp),
                                        tint = MiuixTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
