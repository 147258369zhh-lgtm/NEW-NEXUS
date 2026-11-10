package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
internal fun PreferenceIcon(
    icon: ImageVector,
    tone: EtaIconTone = EtaIconTone.MODEL,
    modifier: Modifier = Modifier,
) {
    EtaIconTile(icon = icon, modifier = modifier, tone = tone)
}
