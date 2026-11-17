package io.github.mangi.eta.ui.screens.capability

import androidx.compose.runtime.*
import io.github.mangi.eta.agent.dsh.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import org.json.JSONObject

/** A refreshed view owns a new subscription even when its package and panel id stay the same. */
@Composable
internal fun DshPanelSubscription(stream: DshStreamClient?, view: DshView?, scope: JSONObject, generation: Long,
    onData: (DshFeed) -> Unit, onEnded: () -> Unit) {
    val currentData by rememberUpdatedState(onData)
    val currentEnded by rememberUpdatedState(onEnded)
    val scopeKey = DshProtocol.optionKey(scope)
    LaunchedEffect(stream, view?.pkg, view?.id, view?.live, scopeKey, generation) {
        if (stream != null && view != null && view.live) {
            val sub = stream.subscribe(view.pkg, view.id, scope, DshFeed(view.data, view.revision))
            try {
                stream.feeds.mapNotNull { it[sub] }.distinctUntilChanged().collect { feed ->
                    if (feed.active) currentData(feed) else currentEnded()
                }
            } finally { stream.unsubscribe(sub) }
        }
    }
}
