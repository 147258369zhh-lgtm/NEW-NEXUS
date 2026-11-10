package io.github.mangi.eta.ui.screens.capability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Webhook
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.connector.ConnectorConfigStore
import io.github.mangi.eta.agent.connector.ConnectorHealth
import io.github.mangi.eta.agent.connector.ConnectorRegistry
import io.github.mangi.eta.agent.connector.SavedConnectorConfig
import io.github.mangi.eta.agent.connector.createSavedConnector
import io.github.mangi.eta.ui.components.DetailListRow
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.net.URI
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ConnectorCenterScreen(
    onBack: () -> Unit,
    onOpenA2A: () -> Unit,
    onOpenMcp: () -> Unit,
    onOpenRest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val registry = remember(context.applicationContext) { ConnectorRegistry(context) }
    val configStore = remember(context.applicationContext) { ConnectorConfigStore(context.applicationContext) }
    var localHealth by remember { mutableStateOf<ConnectorHealth?>(null) }
    var endpoint by remember { mutableStateOf("") }
    var webSocketHealth by remember { mutableStateOf<ConnectorHealth?>(null) }
    var working by remember { mutableStateOf(false) }
    var savedConnectors by remember { mutableStateOf(configStore.load()) }
    var configName by remember { mutableStateOf("") }
    var configProtocol by remember { mutableStateOf("websocket") }
    var configEndpoint by remember { mutableStateOf("") }
    var configSocketPath by remember { mutableStateOf("") }
    var configToken by remember { mutableStateOf("") }
    var configNotice by remember { mutableStateOf<String?>(null) }
    var configError by remember { mutableStateOf<String?>(null) }
    val auditEvents = registry.security().auditEvents()

    LaunchedEffect(Unit) {
        localHealth = registry.localTransport().probe()
    }
    DisposableEffect(registry) {
        onDispose { registry.close() }
    }

    MiuixScaffoldPage(title = "统一连接中心", onBack = onBack) {
        item(key = "intro") {
            SmallTitle("Connector SDK")
            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "统一管理 MCP、A2A、OpenAPI、WebSocket 与 Android 本地能力",
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "每次调用都经过能力声明、主机白名单、危险操作确认和本地审计。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item(key = "manual_config") {
            SmallTitle("连接配置")
            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextField(
                        value = configName,
                        onValueChange = { configName = it.take(80); configError = null },
                        label = "连接名称",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    listOf(
                        listOf("websocket", "jsonrpc", "graphql", "sse"),
                        listOf("unix_socket", "intent", "broadcast", "mediastore"),
                        listOf("sqlite", "shell", "stdio"),
                    ).forEach { protocols ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            protocols.forEach { protocol ->
                                TextButton(
                                    text = protocolLabel(protocol),
                                    onClick = { configProtocol = protocol; configError = null },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(4 - protocols.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    if (configProtocol in SavedConnectorConfig.NETWORK_PROTOCOLS) {
                        TextField(
                            value = configEndpoint,
                            onValueChange = { configEndpoint = it.take(4_096); configError = null },
                            label = "连接地址",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = configToken,
                            onValueChange = { configToken = it.take(8_192) },
                            label = "Bearer Token（可选）",
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (configProtocol == "unix_socket") {
                        TextField(
                            value = configSocketPath,
                            onValueChange = { configSocketPath = it.take(4_096); configError = null },
                            label = "Unix Socket 路径",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    TextButton(
                        text = "保存连接",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            runCatching {
                                configStore.save(
                                    SavedConnectorConfig(
                                        id = "",
                                        name = configName,
                                        protocol = configProtocol,
                                        endpoint = configEndpoint,
                                        socketPath = configSocketPath,
                                    ),
                                    configToken.takeIf(String::isNotBlank),
                                )
                            }.onSuccess {
                                savedConnectors = configStore.load()
                                configName = ""
                                configEndpoint = ""
                                configSocketPath = ""
                                configToken = ""
                                configError = null
                                configNotice = "连接配置已保存，Agent 可使用 connection_id 调用。"
                            }.onFailure { failure ->
                                configNotice = null
                                configError = failure.message ?: "连接配置保存失败"
                            }
                        },
                    )
                    configError?.let { Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1) }
                    configNotice?.let { Text(it, color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.footnote1) }
                }
            }
        }
        if (savedConnectors.isNotEmpty()) {
            item(key = "saved_connections") {
                SmallTitle("已保存连接")
                Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                    savedConnectors.forEach { connector ->
                        DetailListRow(
                            title = connector.name,
                            subtitle = connector.protocol + " · " +
                                connector.endpoint.ifBlank { connector.socketPath }.ifBlank { "本机能力" },
                            icon = Icons.Rounded.Link,
                            value = "测试",
                            onClick = {
                                scope.launch {
                                    working = true
                                    val health = runCatching {
                                        createSavedConnector(
                                            context = context,
                                            config = connector,
                                            bearerToken = configStore.token(connector),
                                            security = registry.security(),
                                        ).use { it.probe() }
                                    }.getOrElse { ConnectorHealth(false, it.message ?: "连接测试失败") }
                                    working = false
                                    configNotice = health.detail
                                    configError = if (health.available) null else health.detail
                                }
                            },
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                text = "删除",
                                onClick = {
                                    configStore.remove(connector.id)
                                    savedConnectors = configStore.load()
                                },
                            )
                        }
                    }
                }
            }
        }
        item(key = "agent_connections") {
            SmallTitle("1 · Agent 与 Agent")
            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                DetailListRow(
                    title = "A2A",
                    subtitle = "Agent Card、双向任务、SSE 流式事件和独立服务进程",
                    icon = Icons.Rounded.Hub,
                    value = "完整支持",
                    onClick = onOpenA2A,
                )
                DetailListRow(
                    title = "WebSocket / JSON-RPC 2.0",
                    subtitle = "双向实时消息；JSON-RPC 使用标准 2.0 请求和结构化错误",
                    icon = Icons.Rounded.Webhook,
                    value = "完整支持",
                )
                DetailListRow(
                    title = "gRPC / WebRTC",
                    subtitle = "需要额外原生运行时、通道协商与媒体栈，当前未打包",
                    icon = Icons.Rounded.PhoneAndroid,
                    value = "暂不支持",
                )
            }
        }
        item(key = "android_local") {
            SmallTitle("2 · Android 应用")
            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                DetailListRow(
                    title = "Intent / Deep Link / PackageManager",
                    subtitle = localHealth?.detail ?: "正在检查 PackageManager 与 ContentResolver",
                    icon = Icons.Rounded.Android,
                    value = if (localHealth?.available == true) "完整支持" else "检查中",
                )
                DetailListRow(
                    title = "ContentProvider / SAF / MediaStore",
                    subtitle = "只读查询、文件 URI 读取和媒体元数据查询",
                    icon = Icons.Rounded.Folder,
                    value = "完整支持",
                )
                DetailListRow(
                    title = "Broadcast / PendingIntent",
                    subtitle = "显式广播已支持；PendingIntent 只能由创建方授权后获得",
                    icon = Icons.Rounded.SettingsEthernet,
                    value = "条件支持",
                )
                DetailListRow(
                    title = "Binder/AIDL / AppFunctions",
                    subtitle = "必须先有目标应用 AIDL 或 AppFunctions 描述；Nexus 不能凭空生成对方接口",
                    icon = Icons.Rounded.Hub,
                    value = "按应用支持",
                )
                DetailListRow(
                    title = "Accessibility",
                    subtitle = "已有无障碍执行链继续作为无公开接口应用的兜底",
                    icon = Icons.Rounded.Android,
                    value = "已有支持",
                )
            }
        }
        item(key = "local_system") {
            SmallTitle("3 · 系统与本机进程")
            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                DetailListRow(
                    title = "STDIO / Shell / PROOT",
                    subtitle = "MCP stdio、Shell 命令、工作目录与本地 Linux 环境共用受控执行边界",
                    icon = Icons.Rounded.Terminal,
                    value = "完整支持",
                )
                DetailListRow(
                    title = "Unix Domain Socket",
                    subtitle = "本机 AF_UNIX/LocalSocket 探测、发送和读取",
                    icon = Icons.Rounded.Dns,
                    value = "完整支持",
                )
                DetailListRow(
                    title = "Root Daemon / Shizuku",
                    subtitle = "Root 走现有终端监督器；Shizuku 需要用户安装并授权独立 Binder Bridge",
                    icon = Icons.Rounded.Security,
                    value = "环境依赖",
                )
            }
        }
        item(key = "websocket") {
            SmallTitle("连接测试")
            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextField(
                        value = endpoint,
                        onValueChange = { endpoint = it.trim().take(2_000) },
                        label = "wss:// 或本机 ws:// 地址",
                        singleLine = true,
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        text = if (working) "连接中…" else "测试连接",
                        enabled = endpoint.isNotBlank() && !working,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                working = true
                                webSocketHealth = runCatching {
                                    URI(endpoint).host?.let(registry.security()::allowHost)
                                    registry.webSocket(endpoint).probe()
                                }.getOrElse { ConnectorHealth(false, it.message ?: "连接失败") }
                                working = false
                            }
                        },
                    )
                    webSocketHealth?.let { health ->
                        Text(
                            text = health.detail,
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (health.available) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        item(key = "external_api") {
            SmallTitle("4 · 外部 API 与工具")
            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                DetailListRow(
                    title = "MCP",
                    subtitle = "HTTP、Streamable HTTP、SSE、资源和提示词",
                    icon = Icons.Rounded.Hub,
                    value = "完整支持",
                    onClick = onOpenMcp,
                )
                DetailListRow(
                    title = "REST / OpenAPI / OAuth",
                    subtitle = "OpenAPI 导入、OAuth 2.0 授权和逐操作授权",
                    icon = Icons.Rounded.Link,
                    value = "完整支持",
                    onClick = onOpenRest,
                )
                DetailListRow(
                    title = "JSON-RPC / GraphQL / SSE",
                    subtitle = "通过 connector_call 探测与调用，带响应上限和错误诊断",
                    icon = Icons.Rounded.Webhook,
                    value = "完整支持",
                )
                DetailListRow(
                    title = "Webhook 接收",
                    subtitle = "需要长期可达的公网/局域网回调和独立监听生命周期，当前版本未启用",
                    icon = Icons.Rounded.SettingsEthernet,
                    value = "暂不支持",
                )
            }
        }
        item(key = "cross_device") {
            SmallTitle("5 · 跨设备与远程")
            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                DetailListRow(
                    title = "HTTP/HTTPS / WebSocket",
                    subtitle = "REST、OpenAPI、JSON-RPC、GraphQL、SSE 和实时双向消息",
                    icon = Icons.Rounded.Computer,
                    value = "完整支持",
                )
                DetailListRow(
                    title = "SSH / ADB / SFTP / SMB",
                    subtitle = "可通过 Shell 连接器调用 PROOT 中已安装的对应客户端；未内置专用协议库",
                    icon = Icons.Rounded.Terminal,
                    value = "环境依赖",
                )
                DetailListRow(
                    title = "BLE / MQTT",
                    subtitle = "需要蓝牙扫描授权、连接池与 MQTT 会话依赖，本版本未接入",
                    icon = Icons.Rounded.Bluetooth,
                    value = "暂不支持",
                )
            }
        }
        item(key = "data") {
            SmallTitle("6 · 文件与数据")
            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                DetailListRow(
                    title = "SAF / Content URI / MediaStore",
                    subtitle = "仅访问用户授权 URI 或系统公开媒体集合",
                    icon = Icons.Rounded.Folder,
                    value = "完整支持",
                )
                DetailListRow(
                    title = "Nexus SQLite",
                    subtitle = "仅允许有界 SELECT/WITH 只读查询，阻止写语句和 PRAGMA",
                    icon = Icons.Rounded.Dns,
                    value = "完整支持",
                )
            }
        }
        item(key = "security") {
            SmallTitle("最近审计")
            Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                if (auditEvents.isEmpty()) {
                    DetailListRow(
                        title = "尚无连接调用",
                        subtitle = "危险操作会在调用前要求确认，并记录到本次运行审计。",
                        icon = Icons.Rounded.SettingsEthernet,
                    )
                } else {
                    auditEvents.takeLast(12).reversed().forEach { event ->
                        DetailListRow(
                            title = event.connectorId,
                            subtitle = event.payload.toString().take(180),
                            icon = Icons.Rounded.Webhook,
                            value = event.type,
                        )
                    }
                }
            }
        }
    }
}

private fun protocolLabel(protocol: String): String = when (protocol) {
    "websocket" -> "WebSocket"
    "jsonrpc" -> "JSON-RPC"
    "graphql" -> "GraphQL"
    "sse" -> "SSE"
    "unix_socket" -> "Unix Socket"
    "intent" -> "Intent"
    "broadcast" -> "Broadcast"
    "mediastore" -> "MediaStore"
    "sqlite" -> "SQLite"
    "shell" -> "Shell"
    "stdio" -> "STDIO"
    else -> protocol
}
