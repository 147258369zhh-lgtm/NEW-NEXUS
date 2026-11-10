package io.github.mangi.eta.ui.screens.capability

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.capability.ApiConnectorAuthType
import io.github.mangi.eta.agent.capability.ApiConnectorDefinition
import io.github.mangi.eta.agent.capability.ApiConnectorService
import io.github.mangi.eta.agent.capability.ApiHttpMethod
import io.github.mangi.eta.agent.capability.ApiOAuthCallbackResult
import io.github.mangi.eta.agent.capability.ApiOAuth2Client
import io.github.mangi.eta.agent.capability.ApiOAuth2Configuration
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.PreferenceIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ApiConnectorsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context.applicationContext) { ApiConnectorService(context) }
    var connectors by remember { mutableStateOf<List<ApiConnectorDefinition>>(emptyList()) }
    var credentialAvailability by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<ApiConnectorDefinition?>(null) }
    var deleteTarget by remember { mutableStateOf<ApiConnectorDefinition?>(null) }
    var pendingDangerousAction by remember { mutableStateOf<Pair<ApiConnectorDefinition, String>?>(null) }
    var credentialTarget by remember { mutableStateOf<ApiConnectorDefinition?>(null) }
    var notice by remember { mutableStateOf<ApiNotice?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val loaded = service.connectors()
                    loaded to loaded.associate { it.id to service.credentialAvailable(it) }
                }
            }.onSuccess { (loaded, credentials) ->
                connectors = loaded
                credentialAvailability = credentials
                selected = selected?.let { current -> loaded.firstOrNull { it.id == current.id } }
            }.onFailure {
                notice = ApiNotice("读取失败", safeApiMessage(it), true)
            }
            loading = false
        }
    }

    fun toggleConnector(connector: ApiConnectorDefinition, enabled: Boolean) {
        if (enabled && connector.actions.none { it.enabled } && connector.activeActions.isEmpty()) {
            notice = ApiNotice(
                "先启用具体操作",
                "连接本身可以立即启用，但在操作页至少启用一项后 Agent 才能调用。",
                false,
            )
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { service.setEnabled(connector.id, enabled) }
            }.onSuccess {
                connectors = it
                selected = selected?.let { current -> it.firstOrNull { item -> item.id == current.id } }
            }.onFailure {
                notice = ApiNotice("连接更新失败", safeApiMessage(it), true)
            }
        }
    }

    fun toggleAction(
        connector: ApiConnectorDefinition,
        actionName: String,
        enabled: Boolean,
    ) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    service.setActionEnabled(connector.id, actionName, enabled)
                }
            }.onSuccess {
                connectors = it
                selected = it.firstOrNull { item -> item.id == connector.id }
            }.onFailure {
                notice = ApiNotice("操作更新失败", safeApiMessage(it), true)
            }
        }
    }

    fun authorize(connector: ApiConnectorDefinition) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { service.beginAuthorization(connector.id) }
            }.onSuccess { request ->
                runCatching { openOAuthPage(context, request.authorizationUrl) }
                    .onFailure {
                        notice = ApiNotice(
                            "无法打开授权页面",
                            safeApiMessage(it) + "。可在连接详情中重新授权。",
                            true,
                        )
                    }
            }.onFailure {
                notice = ApiNotice("无法发起授权", safeApiMessage(it), true)
            }
        }
    }

    LaunchedEffect(refreshGeneration) { reload() }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ApiOAuth2Client(context).consumeLastResult() }?.let { result ->
            notice = ApiNotice(
                if (result is ApiOAuthCallbackResult.Success) "授权完成" else "授权未完成",
                result.message,
                result !is ApiOAuthCallbackResult.Success,
            )
            refreshGeneration += 1
        }
        ApiOAuth2Client.callbackEvents.filterNotNull().collect { result ->
            notice = ApiNotice(
                if (result is ApiOAuthCallbackResult.Success) "授权完成" else "授权未完成",
                result.message,
                result !is ApiOAuthCallbackResult.Success,
            )
            refreshGeneration += 1
        }
    }
    DisposableEffect(Unit) {
        onDispose { }
    }

    MiuixScaffoldPage(
        title = "REST API 连接",
        onBack = onBack,
        actions = {
            IconButton(enabled = !loading, onClick = { showAdd = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "添加 API 连接")
            }
        },
    ) {
        item(key = "api_header") {
            SmallTitle("连接 ${connectors.size}")
            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PreferenceIcon(Icons.Rounded.Api)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("OpenAPI 外部能力", style = MiuixTheme.textStyles.body2)
                        Text(
                            "导入后默认不暴露任何操作；逐项启用后，Agent 才能在当前权限边界内调用。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        if (connectors.isEmpty() && !loading) {
            item(key = "api_empty") {
                SmallTitle("开始连接")
                Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PreferenceIcon(Icons.Rounded.Link)
                        Text("还没有 REST API 连接", style = MiuixTheme.textStyles.body1)
                        Text(
                            "使用 HTTPS 的 OpenAPI 3.x JSON 文档导入，连接凭据保存在 Android Keystore。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        TextButton(text = "添加连接", onClick = { showAdd = true })
                    }
                }
            }
        }
        connectors.forEach { connector ->
            item(key = "api_${connector.id}") {
                SmallTitle(connector.name)
                ApiConnectorCard(
                    connector = connector,
                    credentialAvailable = credentialAvailability[connector.id] == true,
                    onOpen = { selected = connector },
                    onToggle = { toggleConnector(connector, it) },
                )
            }
        }
        if (loading) {
            item(key = "api_loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    InfiniteProgressIndicator(size = 22.dp)
                }
            }
        }
        item(key = "api_security") {
            SmallTitle("安全边界")
            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                ArrowPreference(
                    title = "请求不跟随重定向",
                    summary = "只访问连接声明的主机；响应和文档均有大小上限",
                    startAction = { Icon(Icons.Rounded.Security, contentDescription = null) },
                    onClick = null,
                )
            }
        }
    }

    if (showAdd) {
        AddApiConnectorDialog(
            onDismiss = { showAdd = false },
            onImported = { message ->
                showAdd = false
                notice = ApiNotice("API 连接已添加", message, false)
                refreshGeneration += 1
            },
        )
    }

    selected?.let { connector ->
        ApiConnectorDetailDialog(
            connector = connector,
            credentialAvailable = credentialAvailability[connector.id] == true,
            onDismiss = { selected = null },
            onActionToggle = { name, enabled ->
                val action = connector.actions.firstOrNull { it.name == name }
                if (enabled && action?.destructive == true) {
                    pendingDangerousAction = connector to name
                } else {
                    toggleAction(connector, name, enabled)
                }
            },
            onEditCredential = { credentialTarget = connector },
            onAuthorize = { authorize(connector) },
            onDelete = { deleteTarget = connector },
        )
    }

    credentialTarget?.let { connector ->
        ApiCredentialDialog(
            connector = connector,
            onDismiss = { credentialTarget = null },
            onSaved = {
                credentialTarget = null
                refreshGeneration += 1
                notice = ApiNotice("访问令牌已更新", "新凭据将在下一轮对话中生效。", false)
            },
        )
    }

    pendingDangerousAction?.let { (connector, actionName) ->
        WindowDialog(
            show = true,
            title = "启用可能修改数据的操作？",
            summary = "$actionName 使用 ${connector.actions.firstOrNull { it.name == actionName }?.method.orEmpty()} 请求。启用后，Agent 每次调用仍受全局“REST API 连接”权限组约束。",
            onDismissRequest = { pendingDangerousAction = null },
        ) {
            MiuixDialogActions(
                confirmText = "启用操作",
                destructive = true,
                onCancel = { pendingDangerousAction = null },
                onConfirm = {
                    pendingDangerousAction = null
                    toggleAction(connector, actionName, true)
                },
            )
        }
    }

    deleteTarget?.let { connector ->
        WindowDialog(
            show = true,
            title = "删除 API 连接？",
            summary = "${connector.name} 的操作配置和加密访问令牌将被删除，此操作不可撤销。",
            onDismissRequest = { deleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = "删除连接",
                destructive = true,
                onCancel = { deleteTarget = null },
                onConfirm = {
                    deleteTarget = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { service.delete(connector.id) }
                        }.onSuccess {
                            selected = null
                            notice = ApiNotice("连接已删除", "${connector.name} 已从能力中心移除。", false)
                            refreshGeneration += 1
                        }.onFailure {
                            notice = ApiNotice("删除失败", safeApiMessage(it), true)
                        }
                    }
                },
            )
        }
    }

    notice?.let { current ->
        WindowDialog(
            show = true,
            title = current.title,
            summary = current.message,
            onDismissRequest = { notice = null },
        ) {
            TextButton(
                text = "知道了",
                onClick = { notice = null },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ApiConnectorCard(
    connector: ApiConnectorDefinition,
    credentialAvailable: Boolean,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clickable(onClick = onOpen),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PreferenceIcon(Icons.Rounded.Api)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        connector.name,
                        style = MiuixTheme.textStyles.body2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        connector.baseUrl,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    connector.activeActions.size.toString() + "/" + connector.actions.size,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            SwitchPreference(
                title = "启用连接",
                summary = when {
                    connector.actions.none { it.enabled } -> "待配置操作"
                    !credentialAvailable && connector.authType == ApiConnectorAuthType.OAUTH2 -> "等待 OAuth 授权"
                    !credentialAvailable -> "缺少访问令牌"
                    connector.enabled -> connector.activeActions.size.toString() + " 个操作可供 Agent 使用"
                    else -> "已停用，不向 Agent 暴露操作"
                },
                checked = connector.enabled,
                onCheckedChange = onToggle,
            )
        }
    }
}

@Composable
private fun ApiConnectorDetailDialog(
    connector: ApiConnectorDefinition,
    credentialAvailable: Boolean,
    onDismiss: () -> Unit,
    onActionToggle: (String, Boolean) -> Unit,
    onEditCredential: () -> Unit,
    onAuthorize: () -> Unit,
    onDelete: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = connector.name,
        summary = connector.description.ifBlank { connector.baseUrl },
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 590.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (credentialAvailable) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = if (credentialAvailable) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when {
                            connector.authType == ApiConnectorAuthType.NONE -> "无需认证"
                            connector.authType == ApiConnectorAuthType.OAUTH2 && credentialAvailable -> "OAuth 已授权"
                            connector.authType == ApiConnectorAuthType.OAUTH2 -> "等待 OAuth 授权"
                            credentialAvailable -> "凭据已配置"
                            else -> "凭据待配置"
                        },
                        style = MiuixTheme.textStyles.body2,
                    )
                    Text(
                        connector.baseUrl,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (connector.authType != ApiConnectorAuthType.NONE) {
                    TextButton(
                        text = if (connector.authType == ApiConnectorAuthType.OAUTH2) "重新授权" else "更新令牌",
                        onClick = if (connector.authType == ApiConnectorAuthType.OAUTH2) onAuthorize else onEditCredential,
                    )
                }
            }
            Text(
                "操作权限",
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.SemiBold,
            )
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            ) {
                connector.actions.forEach { action ->
                    SwitchPreference(
                        title = action.name,
                        summary = buildString {
                            append(action.method)
                            append(" ")
                            append(action.path)
                            if (action.description.isNotBlank()) append("\n").append(action.description)
                            if (action.destructive) append("\n可能修改外部数据")
                        },
                        checked = action.enabled,
                        onCheckedChange = { onActionToggle(action.name, it) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = "删除连接",
                    onClick = onDelete,
                )
                Spacer(Modifier.weight(1f))
                TextButton(text = "完成", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun AddApiConnectorDialog(
    onDismiss: () -> Unit,
    onImported: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context.applicationContext) { ApiConnectorService(context) }
    var name by remember { mutableStateOf("") }
    var specUrl by remember { mutableStateOf("") }
    var authType by remember { mutableStateOf(ApiConnectorAuthType.NONE) }
    var headerName by remember { mutableStateOf("X-API-Key") }
    var credential by remember { mutableStateOf("") }
    var authorizationEndpoint by remember { mutableStateOf("") }
    var tokenEndpoint by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var scopes by remember { mutableStateOf("") }
    var usesClientSecret by remember { mutableStateOf(false) }
    var clientSecret by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    WindowDialog(
        show = true,
        title = "添加 REST API 连接",
        summary = "从 OpenAPI 3.x JSON 文档导入操作；所有操作默认关闭，凭据使用 Android Keystore 加密。",
        onDismissRequest = { if (!working) onDismiss() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    label = "显示名称（可选）",
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = specUrl,
                    onValueChange = { specUrl = it.take(2_048) },
                    label = "OpenAPI JSON 文档地址",
                    singleLine = true,
                    enabled = !working,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("认证方式", style = MiuixTheme.textStyles.footnote1)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        ApiConnectorAuthType.NONE to "无认证",
                        ApiConnectorAuthType.BEARER to "Bearer",
                        ApiConnectorAuthType.HEADER to "请求头密钥",
                        ApiConnectorAuthType.OAUTH2 to "OAuth 2.0",
                    ).forEach { (type, label) ->
                        EtaChoiceChip(
                            text = label,
                            selected = authType == type,
                            enabled = !working,
                            onClick = { authType = type },
                        )
                    }
                }
                if (authType == ApiConnectorAuthType.HEADER) {
                    TextField(
                        value = headerName,
                        onValueChange = { headerName = it.take(64) },
                        label = "认证请求头名称",
                        singleLine = true,
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (authType == ApiConnectorAuthType.OAUTH2) {
                    TextField(
                        value = authorizationEndpoint,
                        onValueChange = { authorizationEndpoint = it.take(2_048) },
                        label = "授权端点（HTTPS）",
                        singleLine = true,
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = tokenEndpoint,
                        onValueChange = { tokenEndpoint = it.take(2_048) },
                        label = "令牌端点（HTTPS）",
                        singleLine = true,
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = clientId,
                        onValueChange = { clientId = it.take(512) },
                        label = "Client ID",
                        singleLine = true,
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = scopes,
                        onValueChange = { scopes = it.take(2_048) },
                        label = "Scopes（空格分隔，可选）",
                        singleLine = true,
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    SwitchPreference(
                        title = "使用 Client Secret",
                        summary = "仅用于令牌端点；密钥保存在 Android Keystore",
                        checked = usesClientSecret,
                        onCheckedChange = { usesClientSecret = it },
                    )
                    if (usesClientSecret) {
                        TextField(
                            value = clientSecret,
                            onValueChange = { clientSecret = it.take(16_384) },
                            label = "Client Secret",
                            singleLine = true,
                            enabled = !working,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (authType == ApiConnectorAuthType.BEARER || authType == ApiConnectorAuthType.HEADER) {
                    TextField(
                        value = credential,
                        onValueChange = { credential = it.take(16_384) },
                        label = "访问令牌",
                        singleLine = true,
                        enabled = !working,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let {
                    Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                }
            }
            if (working) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InfiniteProgressIndicator(size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在读取 OpenAPI 文档", style = MiuixTheme.textStyles.footnote1)
                }
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 4.dp),
                confirmText = if (working) "导入中" else "导入连接",
                confirmEnabled = !working && specUrl.isNotBlank() &&
                    when (authType) {
                        ApiConnectorAuthType.NONE -> true
                        ApiConnectorAuthType.OAUTH2 -> authorizationEndpoint.isNotBlank() &&
                            tokenEndpoint.isNotBlank() && clientId.isNotBlank() &&
                            (!usesClientSecret || clientSecret.isNotBlank())
                        else -> credential.isNotBlank()
                    },
                cancelEnabled = !working,
                onCancel = onDismiss,
                onConfirm = {
                    scope.launch {
                        working = true
                        error = null
                        try {
                            val result = service.importOpenApi(
                                requestedName = name,
                                specUrl = specUrl,
                                authType = authType,
                                authHeaderName = headerName,
                                credential = credential,
                                oauth = if (authType == ApiConnectorAuthType.OAUTH2) {
                                    ApiOAuth2Configuration(
                                        authorizationEndpoint = authorizationEndpoint,
                                        tokenEndpoint = tokenEndpoint,
                                        clientId = clientId,
                                        scopes = scopes.split(Regex("\\s+"))
                                            .map(String::trim)
                                            .filter(String::isNotBlank),
                                        usesClientSecret = usesClientSecret,
                                    )
                                } else {
                                    null
                                },
                                oauthClientSecret = clientSecret,
                            )
                            val message = buildString {
                                append(result.connector.actions.size).append(" 个操作已导入，默认全部关闭")
                                if (result.skippedOperations > 0) {
                                    append("；跳过 ").append(result.skippedOperations).append(" 个不兼容操作")
                                }
                                if (result.connector.authType == ApiConnectorAuthType.OAUTH2) {
                                    val opened = runCatching {
                                        val request = withContext(Dispatchers.IO) {
                                            service.beginAuthorization(result.connector.id)
                                        }
                                        openOAuthPage(context, request.authorizationUrl)
                                    }.isSuccess
                                    append(
                                        if (opened) "；已打开授权页面"
                                        else "；未能打开授权页面，请在连接详情中重新授权",
                                    )
                                }
                            }
                            onImported(message)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Throwable) {
                            error = safeApiMessage(failure)
                        } finally {
                            working = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ApiCredentialDialog(
    connector: ApiConnectorDefinition,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context.applicationContext) { ApiConnectorService(context) }
    var credential by remember(connector.id) { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    WindowDialog(
        show = true,
        title = "更新访问令牌",
        summary = "令牌使用 Android Keystore 加密，只会在实际 API 调用时读取。",
        onDismissRequest = { if (!working) onDismiss() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(
                value = credential,
                onValueChange = { credential = it.take(16_384) },
                label = "新的访问令牌",
                singleLine = true,
                enabled = !working,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
            }
            MiuixDialogActions(
                confirmText = if (working) "保存中" else "保存",
                confirmEnabled = !working && credential.isNotBlank(),
                cancelEnabled = !working,
                onCancel = onDismiss,
                onConfirm = {
                    scope.launch {
                        working = true
                        error = null
                        try {
                            withContext(Dispatchers.IO) {
                                service.updateCredential(connector, credential)
                            }
                            credential = ""
                            onSaved()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Throwable) {
                            error = safeApiMessage(failure)
                        } finally {
                            working = false
                        }
                    }
                },
            )
        }
    }
}

private data class ApiNotice(
    val title: String,
    val message: String,
    val isError: Boolean,
)

private fun openOAuthPage(context: Context, authorizationUrl: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl))
    try {
        context.startActivity(intent)
    } catch (failure: ActivityNotFoundException) {
        throw IllegalStateException("设备上没有可打开 HTTPS 授权页面的浏览器", failure)
    }
}

private fun safeApiMessage(failure: Throwable): String = failure.message
    ?.lineSequence()
    ?.firstOrNull()
    ?.trim()
    ?.take(300)
    .orEmpty()
    .ifBlank { "未知错误，请稍后重试" }
