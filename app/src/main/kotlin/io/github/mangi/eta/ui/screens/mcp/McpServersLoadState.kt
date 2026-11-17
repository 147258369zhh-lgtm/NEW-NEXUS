package io.github.mangi.eta.ui.screens.mcp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import io.github.mangi.eta.data.repository.McpServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

internal sealed interface McpServersLoadState {
    data object Loading : McpServersLoadState
    data class Loaded(val servers: List<io.github.mangi.eta.data.model.McpServerSetting>) : McpServersLoadState
    data object Failed : McpServersLoadState
}

internal fun shouldShowMcpServersEmptyState(state: McpServersLoadState): Boolean =
    state is McpServersLoadState.Loaded && state.servers.isEmpty()

@Composable
internal fun rememberMcpServersLoadState(refreshKey: Int): McpServersLoadState {
    val state by produceState<McpServersLoadState>(McpServersLoadState.Loading, refreshKey) {
        value = McpServersLoadState.Loading
        try {
            McpServerRepository.serversFlow().collect { servers ->
                value = McpServersLoadState.Loaded(servers)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = McpServersLoadState.Failed
        }
    }
    return state
}
