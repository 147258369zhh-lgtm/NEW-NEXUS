package io.github.mangi.eta.ui.screens.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.McpHttpClient
import io.github.mangi.eta.agent.mcp.McpClient
import io.github.mangi.eta.agent.mcp.inspectMcp
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Browsing and previewing never runs a prompt or adds remote content to a conversation. */
@Composable
internal fun McpCatalogScreen(serverId: String, onBack: () -> Unit) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    val server = servers.firstOrNull { it.id == serverId }
    var refreshKey by remember { mutableIntStateOf(0) }
    var catalogGeneration by remember { mutableLongStateOf(0L) }
    var loading by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<McpHttpClient.Catalog?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf<McpHttpClient.PromptSummary?>(null) }
    var arguments by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var preview by remember { mutableStateOf<McpHttpClient.ContentPreview?>(null) }
    var previewTitle by remember { mutableStateOf("") }
    var previewError by remember { mutableStateOf<String?>(null) }
    var previewOpen by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var readJob by remember { mutableStateOf<Job?>(null) }
    var readGeneration by remember { mutableLongStateOf(0L) }
    val density = LocalDensity.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    val bodyHeight = (windowHeight * 0.5f).coerceIn(120.dp, 480.dp)

    LaunchedEffect(server, refreshKey) {
        val generation = ++catalogGeneration
        readGeneration++
        readJob?.cancel()
        readJob = null
        reading = false
        previewOpen = false
        prompt = null
        catalog = null
        loading = false
        error = null
        if (server == null) return@LaunchedEffect
        loading = true
        error = null
        try {
            val token = withContext(Dispatchers.IO) { McpServerRepository.bearerToken(server) }
            val loaded = inspectMcp(server, token) { it.discoverCatalog() }
            if (generation == catalogGeneration) catalog = loaded
        } catch (failure: TimeoutCancellationException) {
            if (generation == catalogGeneration) error = resources.getString(R.string.mcp_catalog_timeout)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (generation == catalogGeneration) {
                error = failure.message?.take(500) ?: resources.getString(R.string.mcp_connection_failed)
            }
        } finally {
            if (generation == catalogGeneration) loading = false
        }
    }

    fun read(title: String, operation: (McpClient) -> McpHttpClient.ContentPreview) {
        val current = server ?: return
        val generation = ++readGeneration
        readJob?.cancel()
        previewTitle = title
        preview = null
        previewError = null
        previewOpen = true
        reading = true
        readJob = scope.launch {
            try {
                val token = withContext(Dispatchers.IO) { McpServerRepository.bearerToken(current) }
                val content = inspectMcp(current, token, operation = operation)
                if (generation == readGeneration) preview = content
            } catch (failure: TimeoutCancellationException) {
                if (generation == readGeneration) previewError = resources.getString(R.string.mcp_catalog_timeout)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (generation == readGeneration) {
                    previewError = failure.message?.take(500) ?: resources.getString(R.string.mcp_connection_failed)
                }
            } finally {
                if (generation == readGeneration) {
                    reading = false
                    readJob = null
                }
            }
        }
    }

    fun closePreview() {
        readGeneration++
        readJob?.cancel()
        readJob = null
        reading = false
        previewOpen = false
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.mcp_catalog_title),
        onBack = onBack,
        modifier = Modifier.imePadding(),
        actions = {
            IconButton(enabled = server != null && !loading, onClick = { refreshKey++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.mcp_refresh_tools))
            }
        },
    ) {
        item(key = "catalog_status") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(server?.name ?: stringResource(R.string.mcp_catalog_server_missing), style = MiuixTheme.textStyles.body1)
                    Text(
                        stringResource(R.string.mcp_catalog_preview_note),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (loading) Text(stringResource(R.string.mcp_connecting), style = MiuixTheme.textStyles.body2)
                    error?.let {
                        Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
                        TextButton(text = stringResource(R.string.mcp_catalog_retry), onClick = { refreshKey++ })
                    }
                    if (catalog != null) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            label = stringResource(R.string.mcp_catalog_search),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        catalog?.let { loaded ->
            item(key = "resources_title") { SmallTitle(stringResource(R.string.mcp_catalog_resources)) }
            if (loaded.resourcesTruncated) {
                item(key = "resources_limit") { CatalogNote(stringResource(R.string.mcp_catalog_list_limit)) }
            }
            val filteredResources = loaded.resources.filter {
                query.isBlank() || listOf(it.name, it.uri, it.description).any { text -> text.contains(query.trim(), true) }
            }
            if (filteredResources.isEmpty()) {
                item(key = "resources_empty") {
                    CatalogNote(stringResource(
                        if (!loaded.resourcesSupported) R.string.mcp_resources_unsupported
                        else if (query.isNotBlank()) R.string.mcp_catalog_no_matches else R.string.mcp_catalog_empty,
                    ))
                }
            }
            itemsIndexed(filteredResources, key = { index, _ -> "resource_$index" }) { _, resource ->
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    ArrowPreference(
                        title = resource.name.ifBlank { resource.uri },
                        summary = listOf(resource.description, resource.mimeType, resource.uri)
                            .filter(String::isNotBlank).joinToString("\n"),
                        enabled = !reading,
                        onClick = { read(resource.name.ifBlank { resource.uri }) { it.readResourcePreview(resource.uri) } },
                    )
                }
            }
            item(key = "prompts_title") { SmallTitle(stringResource(R.string.mcp_catalog_prompts)) }
            if (loaded.promptsTruncated) {
                item(key = "prompts_limit") { CatalogNote(stringResource(R.string.mcp_catalog_list_limit)) }
            }
            val filteredPrompts = loaded.prompts.filter {
                query.isBlank() || listOf(it.name, it.title, it.description).any { text -> text.contains(query.trim(), true) }
            }
            if (filteredPrompts.isEmpty()) {
                item(key = "prompts_empty") {
                    CatalogNote(stringResource(
                        if (!loaded.promptsSupported) R.string.mcp_prompts_unsupported
                        else if (query.isNotBlank()) R.string.mcp_catalog_no_matches else R.string.mcp_catalog_empty,
                    ))
                }
            }
            itemsIndexed(filteredPrompts, key = { index, _ -> "prompt_$index" }) { _, item ->
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                    ArrowPreference(
                        title = item.title.ifBlank { item.name },
                        summary = item.description.ifBlank { stringResource(R.string.mcp_prompt_arguments, item.arguments.size) },
                        enabled = !reading,
                        onClick = {
                            arguments = emptyMap()
                            prompt = item
                        },
                    )
                }
            }
        }
    }

    prompt?.let { selected ->
        WindowDialog(
            show = true,
            title = selected.title.ifBlank { selected.name },
            summary = stringResource(R.string.mcp_catalog_preview_note),
            onDismissRequest = { prompt = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.heightIn(max = bodyHeight).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (selected.description.isNotBlank()) Text(selected.description, style = MiuixTheme.textStyles.body2)
                    selected.argumentDefinitions.forEach { argument ->
                        TextField(
                            value = arguments[argument.name].orEmpty(),
                            onValueChange = { value -> arguments = arguments + (argument.name to value) },
                            label = argument.name + if (argument.required) " *" else "",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (argument.description.isNotBlank()) {
                            Text(argument.description, style = MiuixTheme.textStyles.footnote1)
                        }
                    }
                }
                MiuixDialogActions(
                    confirmText = stringResource(R.string.mcp_prompt_preview),
                    confirmEnabled = selected.argumentDefinitions.none { it.required && arguments[it.name].isNullOrBlank() },
                    onCancel = { prompt = null },
                    onConfirm = {
                        val values = arguments.filterValues(String::isNotBlank)
                        prompt = null
                        read(selected.title.ifBlank { selected.name }) { it.getPrompt(selected, values) }
                    },
                )
            }
        }
    }

    WindowDialog(
        show = previewOpen,
        title = previewTitle,
        onDismissRequest = ::closePreview,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.heightIn(max = bodyHeight).verticalScroll(rememberScrollState())) {
                if (reading) Text(stringResource(R.string.mcp_connecting), style = MiuixTheme.textStyles.body2)
                previewError?.let { Text(it, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error) }
                preview?.let { content ->
                    if (content.truncated || content.omittedItems > 0) {
                        Text(
                            stringResource(R.string.mcp_preview_limits, content.omittedItems),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    SelectionContainer {
                        Text(content.text.ifBlank { stringResource(R.string.mcp_preview_empty) }, style = MiuixTheme.textStyles.body2)
                    }
                }
            }
            TextButton(
                text = stringResource(if (reading) R.string.action_cancel else R.string.action_close),
                onClick = ::closePreview,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CatalogNote(text: String) {
    Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        Text(text, style = MiuixTheme.textStyles.body2, modifier = Modifier.padding(16.dp))
    }
}
