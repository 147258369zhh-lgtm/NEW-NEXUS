package io.github.mangi.eta.ui.screens.a2a

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.fork.a2a.A2AAgentEntity
import io.github.mangi.eta.fork.a2a.A2AAgentRepository
import io.github.mangi.eta.fork.a2a.A2AInboundConfigStore
import io.github.mangi.eta.fork.a2a.A2AInboundService
import io.github.mangi.eta.fork.a2a.A2AAccessPolicyStore
import io.github.mangi.eta.fork.a2a.A2AToolAccess
import io.github.mangi.eta.fork.a2a.A2AToolTier
import io.github.mangi.eta.fork.a2a.A2ATransportMode
import io.github.mangi.eta.fork.a2a.A2APairingCode
import io.github.mangi.eta.fork.a2a.A2AArtifactEntity
import io.github.mangi.eta.fork.a2a.A2AArtifactQuarantineState
import io.github.mangi.eta.fork.a2a.A2AHttpClient
import io.github.mangi.eta.fork.a2a.A2APeerPolicy
import io.github.mangi.eta.fork.a2a.A2ATaskEntity
import io.github.mangi.eta.fork.a2a.a2aPeerPolicyForManualEndpoint
import io.github.mangi.eta.fork.a2a.isLocalEndpointHost
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import okhttp3.HttpUrl.Companion.toHttpUrl
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** A2A peer registry. Remote agents remain behind explicit host allowlisting and quarantine. */
@Composable
internal fun A2ASettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context.applicationContext) {
        A2AAgentRepository(
            context = context.applicationContext,
            client = A2AHttpClient(A2APeerPolicy(allowedHosts = emptySet())),
        )
    }
    val inboundStore = remember(context.applicationContext) { A2AInboundConfigStore(context.applicationContext) }
    val accessStore = remember(context.applicationContext) { A2AAccessPolicyStore(context.applicationContext) }
    val observedInbound by inboundStore.observe().collectAsState(initial = inboundStore.snapshot())
    var inbound by remember { mutableStateOf(observedInbound) }
    val observedPeerTiers by accessStore.observePeers().collectAsState(initial = accessStore.peerTiers())
    var peerTiers by remember { mutableStateOf(observedPeerTiers) }
    val agents by repository.observeAgents().collectAsState(initial = emptyList())
    val tasks by repository.observeTasks().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }
    var cardUrl by remember { mutableStateOf("") }
    var bearerToken by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pairingNotice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showInboundToken by remember { mutableStateOf(false) }
    var inboundPermissionError by remember { mutableStateOf<String?>(null) }
    var externalEndpoint by remember { mutableStateOf(inbound.advertisedEndpointUrl.orEmpty()) }
    var transportMode by remember { mutableStateOf(inbound.transportMode) }
    var endpointError by remember { mutableStateOf<String?>(null) }
    var ignoringBatteryOptimizations by remember {
        mutableStateOf(
            context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
        )
    }
    LaunchedEffect(observedInbound) { inbound = observedInbound }
    LaunchedEffect(observedPeerTiers) { peerTiers = observedPeerTiers }
    LaunchedEffect(inboundStore) {
        while (true) {
            delay(2_000)
            inbound = inboundStore.snapshot()
            peerTiers = accessStore.peerTiers()
        }
    }
    LaunchedEffect(inbound.advertisedEndpointUrl, inbound.transportMode) {
        externalEndpoint = inbound.advertisedEndpointUrl.orEmpty()
        transportMode = inbound.transportMode
    }
    val enableInbound = {
        inboundStore.setEnabled(true)
        runCatching { A2AInboundService.start(context.applicationContext) }
            .onFailure { failure ->
                inboundStore.setEnabled(false)
                inboundStore.setLastError(failure.message ?: "无法启动 A2A 服务端")
            }
    }
    LaunchedEffect(inbound.token) {
        if (inbound.token.isNotBlank()) {
            accessStore.observePeer(A2AAccessPolicyStore.tokenPeerId(inbound.token))
        }
    }
    val localNetworkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            inboundPermissionError = null
            enableInbound()
        } else {
            inboundPermissionError = "需要“本地网络”权限，其他设备才能访问 Nexus。"
        }
    }
    val batteryOptimizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        ignoringBatteryOptimizations = context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                ignoringBatteryOptimizations = context.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun importPairingFromClipboard() {
        val raw = readClipboardText(context)
        if (raw.isNullOrBlank()) {
            pairingNotice = null
            error = "剪贴板中没有可导入的 A2A 连接信息"
            showAddDialog = true
            return
        }
        runCatching { A2APairingCode.decode(raw) }
            .onSuccess { pairing ->
                displayName = pairing.displayName
                cardUrl = pairing.agentCardUrl
                bearerToken = pairing.bearerToken.orEmpty()
                error = null
                pairingNotice = "已从剪贴板填入连接信息，请核对后添加。"
                showAddDialog = true
            }
            .onFailure { failure ->
                pairingNotice = null
                error = failure.message ?: "A2A 连接信息格式不正确"
                showAddDialog = true
            }
    }
    val realListening = inbound.enabled &&
        inbound.lastProbeHealthy == true &&
        (inbound.lastProbeAt ?: 0L) > 0L &&
        System.currentTimeMillis() - requireNotNull(inbound.lastProbeAt) < 45_000L

    MiuixScaffoldPage(
        title = "A2A Agent",
        onBack = onBack,
        actions = {
            IconButton(
                enabled = !busy,
                onClick = ::importPairingFromClipboard,
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = "从剪贴板导入 A2A 配对信息")
            }
            IconButton(
                enabled = !busy,
                onClick = {
                    error = null
                    pairingNotice = null
                    showAddDialog = true
                },
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "添加 A2A Agent")
            }
        },
        overlay = {
            WindowDialog(
                show = showAddDialog,
                title = "添加 A2A Agent",
                onDismissRequest = { if (!busy) showAddDialog = false },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            text = "从剪贴板导入配对信息",
                            enabled = !busy,
                            onClick = ::importPairingFromClipboard,
                        )
                        pairingNotice?.let {
                            Text(
                                text = it,
                                color = StatusSuccess,
                                style = MiuixTheme.textStyles.footnote1,
                            )
                        }
                        TextField(
                            value = displayName,
                            onValueChange = { displayName = it },
                            label = "显示名称（可留空，使用 Agent Card 名称）",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = cardUrl,
                            onValueChange = { cardUrl = it.trim() },
                            label = "Agent Card URL（HTTPS 或局域网 HTTP）",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = bearerToken,
                            onValueChange = { bearerToken = it },
                            label = "Bearer Token（可选，存入 Android Keystore）",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "公网地址必须使用 HTTPS。私网 HTTP 仅允许你明确填写的精确局域网主机，避免开放任意内网地址。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        error?.let {
                            Text(it, color = StatusError, style = MiuixTheme.textStyles.footnote1)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    MiuixDialogActions(
                        confirmText = if (busy) "发现中…" else "添加",
                        cancelText = "取消",
                        onCancel = { if (!busy) showAddDialog = false },
                        onConfirm = {
                            if (busy) return@MiuixDialogActions
                            val parsed = runCatching { cardUrl.toHttpUrl() }.getOrNull()
                            if (parsed == null ||
                                (parsed.scheme != "https" &&
                                    !(parsed.scheme == "http" && isLocalEndpointHost(parsed.host)))
                            ) {
                                error = "请输入 HTTPS 地址，或使用明确局域网主机的 HTTP 地址"
                                return@MiuixDialogActions
                            }
                            busy = true
                            error = null
                            scope.launch {
                                runCatching {
                                    A2AAgentRepository(
                                        context = context.applicationContext,
                                        client = A2AHttpClient(a2aPeerPolicyForManualEndpoint(cardUrl)),
                                    ).discoverAndAddAgent(
                                        displayName = displayName,
                                        cardUrl = cardUrl,
                                        bearerToken = bearerToken.takeIf(String::isNotBlank),
                                    )
                                }.onSuccess {
                                    showAddDialog = false
                                    displayName = ""
                                    cardUrl = ""
                                    bearerToken = ""
                                }.onFailure {
                                    error = it.message ?: "Agent Card 发现失败"
                                }
                                busy = false
                            }
                        },
                    )
                }
            }
        },
    ) {
        item(key = "a2a_intro") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = "使用 A2A 1.0 JSON-RPC 双向连接外部 Agent，支持流式任务事件。Nexus 可主动调用已添加的 Agent，也可作为受 Token 保护、独立进程运行的局域网服务端接受请求。远端产物默认隔离，需明确批准。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        item(key = "inbound_title") { SmallTitle("本机作为 A2A 服务端") }
        item(key = "inbound_service") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "允许其他设备调用这台手机",
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = when {
                                    !inbound.enabled -> "关闭 · 不监听任何端口"
                                    realListening -> "真实探测正常 · ${inbound.lastListenAddress ?: "127.0.0.1:${inbound.port}"}"
                                    inbound.lastProbeHealthy == false -> "健康探测失败 · 自动恢复中"
                                    else -> "服务已启动 · 等待真实探测"
                                },
                                style = MiuixTheme.textStyles.footnote2,
                                color = when {
                                    realListening -> StatusSuccess
                                    inbound.enabled && inbound.lastProbeHealthy == false -> StatusError
                                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                            )
                        }
                        Switch(
                            checked = inbound.enabled,
                            onCheckedChange = { enabled ->
                                inboundPermissionError = null
                                if (enabled) {
                                    when (LocalNetworkPermission.accessState(context)) {
                                        LocalNetworkPermission.AccessState.NOT_REQUIRED,
                                        LocalNetworkPermission.AccessState.GRANTED,
                                        -> enableInbound()
                                        LocalNetworkPermission.AccessState.DENIED ->
                                            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                                    }
                                } else {
                                    inboundStore.setEnabled(false)
                                    runCatching { A2AInboundService.stop(context.applicationContext) }
                                }
                            },
                        )
                    }
                    inboundPermissionError?.let { Text(it, color = StatusError, style = MiuixTheme.textStyles.footnote1) }
                    inbound.lastError?.let { Text("服务端错误：$it", color = StatusError, style = MiuixTheme.textStyles.footnote1) }
                    inbound.lastListenAddress?.let {
                        Text(
                            text = "服务地址：$it · 自动重启 ${inbound.restartCount} 次",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TextButton(
                            text = "局域网直连",
                            onClick = {
                                inboundStore.setTransportMode(A2ATransportMode.DIRECT)
                                if (inbound.enabled) {
                                    runCatching { A2AInboundService.restart(context.applicationContext) }
                                }
                            },
                        )
                        TextButton(
                            text = "Tailscale",
                            onClick = {
                                inboundStore.setTransportMode(A2ATransportMode.TAILSCALE)
                                if (inbound.enabled) {
                                    runCatching { A2AInboundService.restart(context.applicationContext) }
                                }
                            },
                        )
                        Text(
                            text = if (transportMode == A2ATransportMode.TAILSCALE) "监听 0.0.0.0，地址由 Tailscale 提供" else "监听 0.0.0.0",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                    TextField(
                        value = externalEndpoint,
                        onValueChange = { externalEndpoint = it.trim().take(500); endpointError = null },
                        label = "对外 JSON-RPC 地址（可选）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            text = "保存对外地址",
                            onClick = {
                                runCatching {
                                    inboundStore.setAdvertisedEndpoint(externalEndpoint.ifBlank { null })
                                    if (inbound.enabled) {
                                        A2AInboundService.restart(context.applicationContext)
                                    }
                                }.onFailure { failure ->
                                    endpointError = failure.message ?: "对外地址无效"
                                }
                            },
                        )
                    }
                    endpointError?.let { Text(it, color = StatusError, style = MiuixTheme.textStyles.footnote1) }
                    Text(
                        text = "未设置对外地址时，Agent Card 会按请求 Host 动态生成。入站请求只能进行模型对话，终端、文件、设备操作和 Subagent 工具均关闭。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (inbound.enabled) {
                        inbound.agentCardUrl?.let { url ->
                            CopyableEndpoint(label = "Agent Card", value = url)
                        }
                        inbound.endpointUrl?.let { url ->
                            CopyableEndpoint(label = "JSON-RPC", value = url)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Bearer Token",
                                style = MiuixTheme.textStyles.footnote1,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                text = if (showInboundToken) "隐藏" else "显示",
                                onClick = { showInboundToken = !showInboundToken },
                            )
                            TextButton(
                                text = "复制",
                                onClick = {
                                    copyText(context, "Nexus A2A Token", inbound.token, sensitive = true)
                                },
                            )
                            TextButton(
                                text = "轮换",
                                onClick = { inboundStore.rotateToken() },
                            )
                        }
                        Text(
                            text = if (showInboundToken) inbound.token else "••••••••••••••••••••••••",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        inbound.agentCardUrl?.let { cardUrl ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Link,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.primary,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "一键配对连接",
                                        style = MiuixTheme.textStyles.body2,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = "包含当前 Bearer Token，只发送给你信任的设备。",
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                                TextButton(
                                    text = "复制",
                                    onClick = {
                                        copyText(
                                            context = context,
                                            label = "Nexus A2A 配对信息",
                                            value = A2APairingCode.encode(
                                                displayName = "Nexus 手机",
                                                agentCardUrl = cardUrl,
                                                bearerToken = inbound.token,
                                            ),
                                            sensitive = true,
                                        )
                                    },
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "开启后会生成仅存放于 Android Keystore 的 Bearer Token。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        item(key = "battery_optimization") {
            SmallTitle("后台稳定性")
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (ignoringBatteryOptimizations) {
                            "已忽略电池优化，息屏监听稳定性较好。"
                        } else {
                            "未加入电池优化白名单，部分系统会在息屏后冻结网络服务。"
                        },
                        style = MiuixTheme.textStyles.body2,
                        color = if (ignoringBatteryOptimizations) StatusSuccess else StatusError,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = "忽略电池优化",
                            enabled = !ignoringBatteryOptimizations,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                val intent = Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                )
                                runCatching { batteryOptimizationLauncher.launch(intent) }
                                    .onFailure { openAppDetails(context) }
                            },
                        )
                        TextButton(
                            text = "应用电池设置",
                            modifier = Modifier.weight(1f),
                            onClick = { openAppBatterySettings(context) },
                        )
                    }
                    Text(
                        text = "ColorOS / MIUI / EMUI：请同时允许后台活动，并在最近任务中给 Nexus 加锁。不同系统名称可能略有差异。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item(key = "inbound_tool_access") {
            SmallTitle("对端工具权限")
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "只读档默认开启；受限档必须逐节点授权；高危档永久关闭，不能通过 A2A 白名单开启。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (peerTiers.isEmpty()) {
                        Text("尚未识别对端。首次请求后会自动记录节点或 Token 指纹。")
                    } else {
                        peerTiers.forEach { (peerId, tier) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(peerId, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = if (tier == A2AToolTier.RESTRICTED) "受限工具已授权" else "只读工具",
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = if (tier == A2AToolTier.RESTRICTED) StatusWarning else StatusSuccess,
                                    )
                                }
                                TextButton(
                                    text = "只读",
                                    onClick = { accessStore.setTier(peerId, A2AToolTier.READ_ONLY) },
                                    enabled = tier != A2AToolTier.READ_ONLY,
                                )
                                TextButton(
                                    text = "授权受限",
                                    onClick = { accessStore.setTier(peerId, A2AToolTier.RESTRICTED) },
                                    enabled = tier != A2AToolTier.RESTRICTED,
                                )
                            }
                            Text(
                                text = "可配置工具：${(if (tier == A2AToolTier.RESTRICTED) A2AToolAccess.RESTRICTED_TOOLS else A2AToolAccess.READ_ONLY_TOOLS).joinToString("、")}",
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = "永久关闭：${A2AToolAccess.HIGH_RISK_TOOLS.joinToString("、")}",
                                style = MiuixTheme.textStyles.footnote2,
                                color = StatusError,
                            )
                        }
                    }
                }
            }
        }
        item(key = "agent_title") { SmallTitle("已连接 Agent") }
        if (agents.isEmpty()) {
            item(key = "agent_empty") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ListEmptyState(
                        title = "尚未添加 A2A Agent",
                        summary = "先取得对方的 Agent Card 地址，再通过右上角添加。",
                        icon = Icons.Rounded.CloudQueue,
                        action = {
                            TextButton(text = "添加 Agent", onClick = { showAddDialog = true })
                        },
                    )
                }
            }
        } else {
            items(agents, key = { it.id }) { agent ->
                A2AAgentCard(
                    agent = agent,
                    onEnabledChange = { enabled ->
                        scope.launch {
                            runCatching { repository.setAgentEnabled(agent.id, enabled) }
                                .onFailure { failure ->
                                    Toast.makeText(
                                        context,
                                        failure.message ?: "无法更新 A2A Agent 状态",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                        }
                    },
                    onRefresh = {
                        scope.launch {
                            runCatching {
                                A2AAgentRepository(
                                    context = context.applicationContext,
                                    client = A2AHttpClient(a2aPeerPolicyForManualEndpoint(agent.cardUrl)),
                                ).refreshAgentCard(agent.id)
                            }
                        }
                    },
                    onDelete = { scope.launch { repository.removeAgent(agent.id) } },
                )
            }
        }
        item(key = "tasks_title") { SmallTitle("远端任务与产物") }
        if (tasks.isEmpty()) {
            item(key = "tasks_empty") {
                Text(
                    text = "还没有 A2A 任务。可从定时任务或后续会议室参与者发起。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        } else {
            items(tasks.take(20), key = { it.localId }) { task ->
                A2ATaskCard(task = task, repository = repository)
            }
        }
    }
}

@Composable
private fun CopyableEndpoint(label: String, value: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
            Text(
                text = value,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(text = "复制", onClick = { copyText(context, label, value) })
    }
}

private fun readClipboardText(context: android.content.Context): String? =
    context.getSystemService(ClipboardManager::class.java)
        ?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        ?.trim()
        ?.takeIf(String::isNotEmpty)

private fun copyText(
    context: android.content.Context,
    label: String,
    value: String,
    sensitive: Boolean = false,
) {
    val clip = ClipData.newPlainText(label, value)
    if (sensitive) {
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    Toast.makeText(context, "已复制$label", Toast.LENGTH_SHORT).show()
}

@Composable
private fun A2AAgentCard(
    agent: A2AAgentEntity,
    onEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = agent.displayName,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${agent.endpointUrl}\nA2A ${agent.protocolVersion} · ${agent.trustState}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (agent.enabled) StatusSuccess else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Switch(
                checked = agent.enabled,
                onCheckedChange = onEnabledChange,
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新 Agent Card")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "删除 Agent",
                    tint = StatusError,
                )
            }
        }
    }
}

@Composable
private fun A2ATaskCard(task: A2ATaskEntity, repository: A2AAgentRepository) {
    val artifacts by repository.observeArtifacts(task.localId).collectAsState(initial = emptyList())
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = task.remoteTaskId ?: task.localId,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = task.state +
                    (task.errorMessage?.let { " · $it" } ?: "") +
                    if (task.cancelUnconfirmed) " · 取消未确认" else "",
                style = MiuixTheme.textStyles.footnote2,
                color = when {
                    task.errorCode != null || task.cancelUnconfirmed -> StatusError
                    task.state.contains("COMPLETED") -> StatusSuccess
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (artifacts.isNotEmpty()) {
                artifacts.forEach { artifact ->
                    A2AArtifactRow(task.localId, artifact, repository)
                }
            }
        }
    }
}

@Composable
private fun A2AArtifactRow(
    taskId: String,
    artifact: A2AArtifactEntity,
    repository: A2AAgentRepository,
) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artifact.name ?: artifact.artifactId,
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${artifact.mediaTypeSummary ?: "未知类型"} · ${artifact.quarantineState}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        TextButton(
            text = "拒绝",
            onClick = {
                scope.launch {
                    repository.setArtifactQuarantineState(
                        taskId,
                        artifact.artifactId,
                        A2AArtifactQuarantineState.REJECTED,
                    )
                }
            },
        )
        TextButton(
            text = "批准",
            onClick = {
                scope.launch {
                    repository.setArtifactQuarantineState(
                        taskId,
                        artifact.artifactId,
                        A2AArtifactQuarantineState.APPROVED,
                    )
                }
            },
        )
    }
}

private fun openAppBatterySettings(context: android.content.Context) {
    val uri = Uri.parse("package:${context.packageName}")
    val battery = Intent("android.settings.APPLICATION_BATTERY_SETTINGS", uri)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(battery) }.onFailure { openAppDetails(context) }
}

private fun openAppDetails(context: android.content.Context) {
    val details = Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(details) }
}
