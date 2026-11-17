package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.OnlineImageGenerationConfig
import io.github.mangi.eta.data.repository.CloudToolsSettingsRepository
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField

internal fun onlineImageKeyReplacement(key: String, changed: Boolean): String? =
    key.takeIf { changed && it.isNotBlank() }

/** Shares the established cloud media executor and artifact owner, without a second image store. */
@Composable
internal fun OnlineImageSettingsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<OnlineImageGenerationConfig?>(null) }
    var draft by remember { mutableStateOf(OnlineImageGenerationConfig()) }
    var key by remember { mutableStateOf("") }
    var keyChanged by remember { mutableStateOf(false) }
    var keyConfigured by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            val config = CloudToolsSettingsRepository.settings().onlineImage
            draft = config
            keyConfigured = CloudToolsSettingsRepository.credentials(context).onlineImageApiKey.isNotBlank()
            saved = config
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "图片设置或凭据读取失败，请返回后重试" }
    }
    MiuixScaffoldPage(title = "在线图片模型", onBack = onBack, actions = {
        TextButton(text = if (saving) "保存中" else "保存", enabled = saved != null && !saving,
            onClick = {
                val expected = saved ?: return@TextButton
                val value = draft
                val changedKey = onlineImageKeyReplacement(key, keyChanged)
                saving = true
                scope.launch {
                    try {
                        CloudToolsSettingsRepository.saveOnlineImage(context, value, changedKey, expected)
                        saved = value
                        if (changedKey != null) keyConfigured = true
                        key = ""; keyChanged = false
                        message = "已保存；在绘图或会议中请求生成图片即可使用"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { message = error.message ?: "保存失败，请检查设置" }
                    finally { saving = false }
                }
            })
    }) {
        item(key = "online_image_fields") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SwitchPreference(title = "启用在线文生图", summary = "用于普通绘图与会议中的 cloud_media 图片生成",
                    checked = draft.enabled, enabled = saved != null && !saving, onCheckedChange = { draft = draft.copy(enabled = it) })
                Text("接口协议：OpenAI Images（同步 /images/generations）。支持 data 中的 PNG、JPEG、WebP base64 或公开 HTTPS 图片地址；每次最多 4 张。")
                TextField(value = draft.baseUrl, onValueChange = { draft = draft.copy(baseUrl = it) },
                    label = "API 地址（包含 /v1，或完整生成地址）", singleLine = true, enabled = saved != null && !saving,
                    modifier = Modifier.fillMaxWidth())
                TextField(value = draft.model, onValueChange = { draft = draft.copy(model = it.trim()) },
                    label = "图片模型 ID", singleLine = true, enabled = saved != null && !saving, modifier = Modifier.fillMaxWidth())
                TextField(value = key, onValueChange = { key = it; keyChanged = true },
                    label = if (keyConfigured) "API Key（已保存；留空保留，输入后替换）" else "API Key",
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = saved != null && !saving,
                    modifier = Modifier.fillMaxWidth())
                Text("响应格式：默认不发送 response_format，适用于 GPT 图片模型；指定格式须由你的服务商支持。")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "服务商默认", "b64_json" to "Base64", "url" to "图片地址").forEach { (value, label) ->
                        EtaChoiceChip(text = label, selected = draft.responseFormat == value, enabled = saved != null && !saving,
                            onClick = { draft = draft.copy(responseFormat = value) })
                    }
                }
                Text("生成可能计费。运行时复用请求记录，失败或中断后不会自动重新付费提交。其他协议、异步接口和图片编辑请使用已有云能力设置。")
                message?.let { Text(it) }
            }
        }
    }
}
