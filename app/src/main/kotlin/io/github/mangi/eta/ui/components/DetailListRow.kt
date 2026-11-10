package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DetailListRow(title: String, subtitle: String? = null, icon: ImageVector,
    preview: String? = null, value: String? = null, enabled: Boolean = true,
    tone: EtaIconTone = EtaIconTone.MODEL,
    onClick: (() -> Unit)? = null) {
    val interactionSource = rememberEtaInteractionSource()
    EtaInteractiveRow(
        interactionSource = interactionSource,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    enabled = enabled,
                    interactionSource = interactionSource,
                    indication = etaRippleIndication(),
                    onClick = onClick,
                )
            } else Modifier,
        )
        .alpha(if (enabled) 1f else 0.48f)
        .padding(horizontal = EtaUiMetrics.cardPadding, vertical = 12.dp),
    ) {
        PreferenceIcon(icon, tone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MiuixTheme.textStyles.body2.copy(fontSize = EtaTypography.rowTitleSize, lineHeight = EtaTypography.rowTitleLineHeight), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            preview?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MiuixTheme.textStyles.body2.copy(fontSize = EtaTypography.valueSize, lineHeight = EtaTypography.valueLineHeight), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MiuixTheme.textStyles.footnote2.copy(fontSize = EtaTypography.supportingSize, lineHeight = EtaTypography.supportingLineHeight), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            value?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary) }
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
