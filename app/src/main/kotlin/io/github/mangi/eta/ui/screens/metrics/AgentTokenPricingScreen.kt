package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentTokenPrice
import io.github.mangi.eta.agent.runtime.AgentTokenPriceStore
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private data class PriceModel(val provider: String, val model: String, val name: String, val providerName: String)

@Composable
internal fun AgentTokenPricingScreen(onBack: () -> Unit, onEdit: (String, String) -> Unit) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    val prices by remember(context, reload) {
        AgentTokenPriceStore.prices(context).onEach { loading = false; failed = false }.catch { loading = false; failed = true }
    }.collectAsState(initial = emptyList())
    var providersFailed by remember { mutableStateOf(false) }
    val providers by remember(reload) {
        ProviderRepository.providersFlow().onEach { providersFailed = false }.catch { providersFailed = true }
    }.collectAsState(initial = emptyList())
    var search by rememberSaveable { mutableStateOf("") }
    val configured = providers.flatMap { provider -> provider.models.map { model ->
        PriceModel(provider.id, model.modelId, model.displayName.ifBlank { model.modelId }, provider.name)
    } }
    val models = (configured + prices.map { PriceModel(it.providerId, it.modelId, it.modelId, it.providerId) })
        .distinctBy { it.provider to it.model }
        .sortedWith(compareByDescending<PriceModel> { model -> prices.any { it.providerId == model.provider && it.modelId == model.model } }
            .thenBy { it.providerName }.thenBy { it.name })
        .filter { search.isBlank() || listOf(it.name, it.model, it.providerName).any { text -> text.contains(search.trim(), ignoreCase = true) } }
    MiuixScaffoldPage(title = stringResource(R.string.metrics_prices_title), onBack = onBack) {
        item(key = "note") {
            Card(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.metrics_prices_note), style = MiuixTheme.textStyles.body2)
                    Text(stringResource(R.string.metrics_prices_history_note), style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    TextField(value = search, onValueChange = { search = it.take(128) },
                        label = stringResource(R.string.metrics_prices_search), singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (loading) Text(stringResource(R.string.metrics_loading))
                    if (failed || providersFailed) {
                        Text(stringResource(R.string.metrics_load_failed), color = MiuixTheme.colorScheme.error)
                        TextButton(stringResource(R.string.metrics_refresh), onClick = { loading = true; reload++ })
                    }
                }
            }
        }
        if (models.isEmpty() && !loading) item(key = "empty") {
            Text(stringResource(R.string.metrics_prices_empty), Modifier.padding(24.dp), style = MiuixTheme.textStyles.body2)
        }
        items(models, key = { "${it.provider.length}:${it.provider}${it.model}" }) { model ->
            val price = prices.firstOrNull { it.providerId == model.provider && it.modelId == model.model }?.price
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
                ArrowPreference(title = model.name,
                    summary = model.providerName + " · " + model.model + "\n" +
                        if (price == null) stringResource(R.string.metrics_prices_unset)
                        else stringResource(R.string.metrics_prices_rates, price.currency, price.input, price.output),
                    enabled = !loading && !failed, onClick = { onEdit(model.provider, model.model) })
            }
        }
    }
}

@Composable
internal fun AgentTokenPriceEditorScreen(providerId: String, modelId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var hasPrice by remember { mutableStateOf(false) }
    var initialized by rememberSaveable(providerId, modelId) { mutableStateOf(false) }
    var currency by rememberSaveable(providerId, modelId) { mutableStateOf("USD") }
    var input by rememberSaveable(providerId, modelId) { mutableStateOf("") }
    var output by rememberSaveable(providerId, modelId) { mutableStateOf("") }
    var read by rememberSaveable(providerId, modelId) { mutableStateOf("") }
    var write by rememberSaveable(providerId, modelId) { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var modelName by remember { mutableStateOf(modelId) }
    var providerName by remember { mutableStateOf(providerId) }
    LaunchedEffect(providerId, modelId, reload) {
        loading = true
        loadFailed = false
        try {
            val price = withContext(Dispatchers.IO) { AgentTokenPriceStore.resolve(context, providerId, modelId) }
            hasPrice = price != null
            withContext(Dispatchers.IO) { runCatching { ProviderRepository.allProviders() }.getOrNull() }
                ?.firstOrNull { it.id == providerId }?.let { provider ->
                    providerName = provider.name
                    modelName = provider.models.firstOrNull { it.modelId == modelId }?.displayName?.ifBlank { modelId } ?: modelId
                }
            if (!initialized) {
                currency = price?.currency ?: "USD"; input = price?.input.orEmpty(); output = price?.output.orEmpty()
                read = price?.cacheRead.orEmpty(); write = price?.cacheWrite.orEmpty(); initialized = true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true }
        finally { loading = false }
    }
    val validPrice = remember(currency, input, output, read, write) {
        runCatching { AgentTokenPrice.create(currency, input, output, read, write) }.getOrNull()
    }
    MiuixScaffoldPage(title = stringResource(R.string.metrics_prices_edit), onBack = { if (!busy) onBack() }) {
        item(key = "model") {
            Card(Modifier.padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 12.dp)) {
                BasicComponent(title = modelName, summary = providerName + " · " + modelId + "\n" + stringResource(R.string.metrics_prices_history_note))
            }
        }
        item(key = "form") {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.metrics_prices_unit), style = MiuixTheme.textStyles.body1)
                    Text(stringResource(R.string.metrics_prices_rules), style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    TextField(value = currency, onValueChange = { currency = it.take(3); error = null },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = KeyboardCapitalization.Characters),
                        label = stringResource(R.string.metrics_prices_currency), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy && !loading)
                    TextField(value = input, onValueChange = { input = it.take(20); error = null },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = stringResource(R.string.metrics_prices_input), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy && !loading)
                    TextField(value = output, onValueChange = { output = it.take(20); error = null },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = stringResource(R.string.metrics_prices_output), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy && !loading)
                    TextField(value = read, onValueChange = { read = it.take(20); error = null },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = stringResource(R.string.metrics_prices_read), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy && !loading)
                    TextField(value = write, onValueChange = { write = it.take(20); error = null },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = stringResource(R.string.metrics_prices_write), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy && !loading)
                    if (loading) Text(stringResource(R.string.metrics_loading))
                    if (loadFailed) {
                        Text(stringResource(R.string.metrics_load_failed), color = MiuixTheme.colorScheme.error)
                        TextButton(stringResource(R.string.metrics_refresh), onClick = { reload++ })
                    }
                    error?.let { Text(stringResource(it), color = MiuixTheme.colorScheme.error) }
                    if (input.isNotBlank() && output.isNotBlank() && validPrice == null) {
                        Text(stringResource(R.string.metrics_prices_invalid), style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error)
                    }
                    TextButton(stringResource(if (busy) R.string.metrics_prices_saving else R.string.metrics_prices_save),
                        enabled = !busy && !loading && !loadFailed && validPrice != null, onClick = {
                            val price = runCatching { AgentTokenPrice.create(currency, input, output, read, write) }.getOrNull()
                            if (price == null) { error = R.string.metrics_prices_invalid; return@TextButton }
                            busy = true
                            scope.launch {
                                try { AgentTokenPriceStore.save(context, providerId, modelId, price); onBack() }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { error = R.string.metrics_prices_failed }
                                finally { busy = false }
                            }
                        })
                }
            }
        }
        if (hasPrice) item(key = "remove") {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference(title = stringResource(R.string.metrics_prices_remove), enabled = !busy && !loading && !loadFailed,
                    summary = stringResource(R.string.metrics_prices_remove_note), onClick = { confirmRemove = true })
            }
        }
    }
    WindowDialog(show = confirmRemove, title = stringResource(R.string.metrics_prices_remove),
        summary = stringResource(R.string.metrics_prices_remove_note), onDismissRequest = { if (!busy) confirmRemove = false }) {
        MiuixDialogActions(confirmText = stringResource(R.string.metrics_prices_remove), destructive = true,
            confirmEnabled = !busy, cancelEnabled = !busy, onCancel = { confirmRemove = false }, onConfirm = {
                busy = true
                scope.launch {
                    try { AgentTokenPriceStore.remove(context, providerId, modelId); onBack() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = R.string.metrics_prices_failed; confirmRemove = false }
                    finally { busy = false }
                }
            })
    }
}
