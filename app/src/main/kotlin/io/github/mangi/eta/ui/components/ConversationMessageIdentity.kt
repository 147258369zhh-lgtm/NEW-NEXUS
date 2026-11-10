package io.github.mangi.eta.ui.components

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class ConversationAuthorRole { User, Agent, Host, System }

/** Optional attribution for a shared message row; it never carries a live task's status. */
@Immutable
internal data class ConversationMessageIdentity(
    val name: String,
    val role: ConversationAuthorRole,
    val createdAt: Long,
    val isSummary: Boolean = false,
    /** Null means ordinary full content; zero means a title-only collapsed summary. */
    val previewLines: Int? = null,
    /** Keep parallel participants' adjacent work processes separate. */
    val workGroup: String? = null,
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ConversationAuthorHeader(identity: ConversationMessageIdentity) {
    val user = identity.role == ConversationAuthorRole.User
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        // Leave the name its own line when scaled metadata would squeeze it to a few letters.
        val stackedMetadata = maxWidth / LocalDensity.current.fontScale < 280.dp
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, if (user) Alignment.End else Alignment.Start),
            ) {
                if (!user) {
                    Box(
                        modifier = Modifier.size(24.dp).background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = identity.name.take(if (identity.name.isEmpty()) 0 else identity.name.offsetByCodePoints(0, 1)),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = identity.name,
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = if (stackedMetadata) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = !user),
                )
                if (!stackedMetadata) ConversationIdentityMetadata(identity)
            }
            if (stackedMetadata) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(start = if (user) 0.dp else 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, if (user) Alignment.End else Alignment.Start),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ConversationIdentityMetadata(identity)
                }
            }
        }
    }
}

@Composable
private fun ConversationIdentityMetadata(identity: ConversationMessageIdentity) {
    if (identity.isSummary || identity.role == ConversationAuthorRole.Host || identity.role == ConversationAuthorRole.System) {
        Text(
            text = stringResource(when {
                identity.isSummary -> R.string.conversation_message_summary
                identity.role == ConversationAuthorRole.Host -> R.string.conversation_message_host
                else -> R.string.conversation_message_system
            }),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    Text(
        text = DateFormat.getTimeFormat(LocalContext.current).format(identity.createdAt),
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        maxLines = 1,
    )
}
