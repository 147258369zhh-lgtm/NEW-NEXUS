package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val ComposerContainerShape = smoothShape(28.dp)

/** Shared by conversation and meeting input; controls and editor behavior stay with their owners. */
@Composable
internal fun ConversationComposerSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        val glass = LocalEtaDesignTokens.current.skin.isGlass
        if (glass) NexusGlassBackdrop(Modifier.matchParentSize(), cornerRadius = 28.dp, refract = false)
        Column(
            modifier = Modifier.fillMaxWidth()
                .then(if (glass) Modifier.etaGlassSurface(cornerRadius = 28.dp) else Modifier
                    .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.92f), ComposerContainerShape)
                    .border(0.5.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.42f), ComposerContainerShape))
                .dropShadow(ComposerContainerShape, Shadow(radius = 8.dp, color = Color.Black, alpha = 0.08f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            content = content,
        )
    }
}
