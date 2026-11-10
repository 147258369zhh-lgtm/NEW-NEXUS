package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.PendingActions
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared knowledge summary used by layered memory and the self-learning review surface. */
@Composable
internal fun KnowledgeMetricsCard(
    activeCount: Int,
    pendingCount: Int,
    conflictCount: Int,
    activeLabel: String,
    pendingLabel: String,
    conflictLabel: String,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Card(modifier = modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KnowledgeMetric(
                value = activeCount,
                label = activeLabel,
                icon = Icons.Rounded.Bookmarks,
                tint = colors.primary,
                modifier = Modifier.weight(1f),
            )
            KnowledgeMetricDivider()
            KnowledgeMetric(
                value = pendingCount,
                label = pendingLabel,
                icon = Icons.Rounded.PendingActions,
                tint = colors.primary,
                modifier = Modifier.weight(1f),
            )
            KnowledgeMetricDivider()
            KnowledgeMetric(
                value = conflictCount,
                label = conflictLabel,
                icon = Icons.Rounded.WarningAmber,
                tint = if (conflictCount > 0) colors.error else colors.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun KnowledgeMetricDivider() {
    Box(
        Modifier
            .width(0.5.dp)
            .height(52.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

@Composable
private fun KnowledgeMetric(
    value: Int,
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = tint,
        )
        Text(
            text = value.toString(),
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
