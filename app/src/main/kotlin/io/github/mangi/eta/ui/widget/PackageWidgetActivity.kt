package io.github.mangi.eta.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.agent.packages.PackageDesktopWidget
import io.github.mangi.eta.agent.packages.PackageDesktopWidgetService
import io.github.mangi.eta.agent.packages.PackageUiModule
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.DetailListRow
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.etaGlassSurface
import io.github.mangi.eta.ui.screens.capability.PackageUiScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

class PackageWidgetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        enableEdgeToEdge()
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val service = PackageDesktopWidgetService(this)
        if (!service.isOwned(id)) { finish(); return }
        val configuring = intent.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE
        lifecycleScope.launch {
            val appearance = AppearanceSettingsRepository.settings()
            var initial: PackageUiModule? = null
            var choices = emptyList<PackageDesktopWidget>()
            var initialError: String? = null
            try {
                withContext(Dispatchers.IO) {
                    if (!configuring) initial = service.resolve(id)?.let { service.module(it, render = false) }
                    choices = service.available()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { initialError = "组件暂不可用，请重新选择" }
            setContent {
                AgentAppTheme(appearance, applyInterfaceScale = true) {
                    var module by remember { mutableStateOf(initial) }
                    var error by remember { mutableStateOf(initialError) }
                    var busy by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()
                    if (module != null) PackageUiScreen(requireNotNull(module), onBack = { finish() })
                    else MiuixScaffoldPage("桌面组件", onBack = { finish() }) {
                        error?.let { message -> item { Text(message, style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
                        if (choices.isEmpty()) item { Text("暂无可用组件", modifier = Modifier.padding(24.dp), style = MiuixTheme.textStyles.body2) }
                        choices.forEach { value -> item(key = "${value.record.id}/${value.id}") {
                            Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).etaGlassSurface()) {
                                DetailListRow(value.title, icon = Icons.Rounded.Widgets, tone = EtaIconTone.TOOL, enabled = !busy, onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            withContext(Dispatchers.IO) { service.bind(id, value) }
                                            if (configuring) {
                                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                                            } else module = withContext(Dispatchers.IO) { service.module(value, render = false) }
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                        catch (_: Exception) { error = "组件已变化，请重新添加" }
                                        finally { busy = false }
                                    }
                                })
                            }
                        } }
                    }
                    io.github.mangi.eta.ui.screens.control.EtaAppControlConfirmationHost()
                }
            }
        }
    }
}
