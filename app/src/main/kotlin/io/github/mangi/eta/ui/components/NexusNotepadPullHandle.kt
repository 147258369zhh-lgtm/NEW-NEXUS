package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A small top-edge handle keeps the note gesture discoverable without stealing list scrolling. */
@Composable
internal fun NexusNotepadPullHandle(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val drag = remember { mutableFloatStateOf(0f) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .pointerInput(onOpen) {
                detectVerticalDragGestures(
                    onDragStart = { drag.floatValue = 0f },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        drag.floatValue += amount
                    },
                    onDragEnd = {
                        if (drag.floatValue > 42f) onOpen()
                        drag.floatValue = 0f
                    },
                    onDragCancel = { drag.floatValue = 0f },
                )
            }
            .pointerInput(onOpen) { detectTapGestures(onTap = { onOpen() }) },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .width(30.dp)
                .height(3.dp)
                .background(
                    MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.20f),
                    RoundedCornerShape(50),
                ),
        )
    }
}
