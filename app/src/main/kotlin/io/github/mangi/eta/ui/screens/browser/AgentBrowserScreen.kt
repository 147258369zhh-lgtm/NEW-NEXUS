package io.github.mangi.eta.ui.screens.browser

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import io.github.mangi.eta.ui.components.NexaCompactAction
import io.github.mangi.eta.ui.components.NexusGlassBackdrop
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.net.toUri
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.ui.components.MiuixDialogActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 挂载会话原有的 WebView，页面自身持有地址栏及系统栏 Insets。 */
@Composable
internal fun AgentBrowserScreen(
    session: AgentBrowserSession,
    onBack: () -> Unit,
    onScanQr: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val noExternalAppMessage = stringResource(R.string.browser_no_external_app)
    val snapshot by session.snapshots.collectAsState()
    var address by remember(session) { mutableStateOf(TextFieldValue("")) }
    var addressFocused by remember(session) { mutableStateOf(false) }
    var actionPending by remember(session) { mutableStateOf(false) }
    var showClearDataDialog by remember { mutableStateOf(false) }

    // 编辑期间不覆盖用户输入；非编辑态仅显示域名，进入编辑时还原完整地址。
    LaunchedEffect(addressFocused) {
        if (addressFocused) {
            address = TextFieldValue(snapshot.displayUrl, TextRange(0, snapshot.displayUrl.length))
        }
    }
    LaunchedEffect(snapshot.displayUrl, snapshot.host, addressFocused) {
        if (!addressFocused) address = TextFieldValue(snapshot.host.ifBlank { snapshot.displayUrl })
    }
    LaunchedEffect(snapshot.error) {
        snapshot.error?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    fun launchBrowserAction(action: () -> Unit) {
        if (actionPending) return
        actionPending = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.warn("Browser user action failed: type=${failure.safeLogType()}")
                Toast.makeText(context, R.string.browser_user_action_failed, Toast.LENGTH_SHORT).show()
            } finally {
                actionPending = false
            }
        }
    }

    fun navigate() {
        val input = address.text.trim()
        val target = if (input.isNotBlank() && input == snapshot.displayUrl) {
            snapshot.url
        } else {
            resolveBrowserAddress(input) ?: return
        }
        if (target.isBlank() || actionPending) return
        focusManager.clearFocus()
        keyboard?.hide()
        launchBrowserAction { session.navigateFromUser(context.applicationContext, target) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .imePadding()
            .navigationBarsPadding(),
    ) {
        BrowserAddressBar(
            snapshot = snapshot,
            address = address,
            onAddressChange = { address = it },
            focused = addressFocused,
            onFocusChange = { addressFocused = it },
            actionPending = actionPending,
            onExit = onBack,
            onGo = ::navigate,
            onBack = { launchBrowserAction { session.goBackFromUser() } },
            onForward = { launchBrowserAction { session.goForwardFromUser() } },
            onRefreshOrStop = {
                if (snapshot.isLoading) {
                    session.stopFromUser()
                } else {
                    launchBrowserAction { session.reloadFromUser() }
                }
            },
            onOpenExternal = {
                if (snapshot.url.startsWith("http://") || snapshot.url.startsWith("https://")) {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, snapshot.url.toUri()))
                    } catch (_: android.content.ActivityNotFoundException) {
                        Toast.makeText(context, noExternalAppMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onClosePage = {
                focusManager.clearFocus()
                keyboard?.hide()
                launchBrowserAction { session.resetFromUser() }
            },
            onClearData = { showClearDataDialog = true },
            onScanQr = onScanQr,
            onFinishTakeover = onBack,
        )
        // Keep a fixed progress slot so loading does not repeatedly resize the WebView.
        Row(Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = snapshot.title.ifBlank { stringResource(R.string.page_agent_browser_a66bd5) },
                modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (snapshot.isLoading) Text(
                text = stringResource(R.string.tool_browser_loading, snapshot.progress),
                modifier = Modifier.padding(start = 12.dp),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Box(Modifier.fillMaxWidth().height(3.dp)) {
            if (snapshot.isLoading) Box(
                Modifier.fillMaxWidth(snapshot.progress.coerceIn(0, 100) / 100f).fillMaxHeight()
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = .65f), RoundedCornerShape(2.dp)),
            )
        }
        snapshot.error?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message, Modifier.weight(1f), style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
                NexaCompactAction(text = stringResource(R.string.browser_refresh), enabled = snapshot.available && !actionPending, onClick = { launchBrowserAction { session.reloadFromUser() } })
            }
        }
        BrowserWebViewHost(session = session, modifier = Modifier.weight(1f))
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val tokens = LocalEtaDesignTokens.current
            if (tokens.skin.isGlass) NexusGlassBackdrop(Modifier.matchParentSize(), cornerRadius = 22.dp, refract = false)
            else Box(Modifier.matchParentSize().background(tokens.surfaceElevated, RoundedCornerShape(22.dp)))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NexaCompactAction(text = stringResource(R.string.browser_scan_qr), icon = Icons.Rounded.QrCodeScanner, enabled = !actionPending, onClick = onScanQr)
                NexaCompactAction(text = stringResource(R.string.browser_finish_takeover), icon = Icons.AutoMirrored.Rounded.ArrowBack, onClick = onBack)
            }
        }
    }

    if (showClearDataDialog) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.browser_clear_shared_data),
            summary = stringResource(R.string.browser_clear_shared_data_message),
            onDismissRequest = { showClearDataDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.browser_clear_shared_data),
                confirmEnabled = !actionPending,
                destructive = true,
                onCancel = { showClearDataDialog = false },
                onConfirm = {
                    showClearDataDialog = false
                    launchBrowserAction { BrowserSessionRegistry.clearSharedBrowsingData(context.applicationContext) }
                },
            )
        }
    }
}
