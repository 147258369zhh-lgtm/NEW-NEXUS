package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.MessageFont
import io.github.mangi.eta.data.model.MessageTypographyPreset
import io.github.mangi.eta.data.model.MessageTypographySettings
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.NexaCompactAction
import io.github.mangi.eta.ui.components.NexaSegmentedTabs
import io.github.mangi.eta.ui.layout.WidePageContent
import io.github.mangi.eta.ui.components.NexusSettingsCard
import io.github.mangi.eta.ui.components.NexusSettingsDropdownPreference
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference
import io.github.mangi.eta.ui.markdown.MarkdownTone
import io.github.mangi.eta.ui.markdown.StaticMarkdown
import io.github.mangi.eta.ui.markdown.messageBodyTextStyle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SliderPreference
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

@Composable
internal fun MessageTypographyScreen(onBack: () -> Unit) {
    val appearance = LocalAppearanceSettings.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(appearance.messageTypography.normalized()) }
    var saved by remember { mutableStateOf(appearance.messageTypography.normalized()) }
    var saveFailure by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(TypographyPreviewSection.Body) }
    var writeRevision by remember { mutableStateOf(0) }
    val screenHeight = LocalConfiguration.current.screenHeightDp
    var compactPreview by remember(screenHeight < 500) { mutableStateOf(screenHeight < 500) }
    val previewHeight = if (compactPreview) 136.dp else (screenHeight * .32f).coerceIn(156f, 260f).dp
    LaunchedEffect(appearance.messageTypography) {
        if (draft == saved) draft = appearance.messageTypography.normalized()
        saved = appearance.messageTypography.normalized()
    }
    fun save(value: MessageTypographySettings) {
        draft = value.normalized()
        val snapshot = draft
        val revision = ++writeRevision
        val pending = AppearanceSettingsRepository.saveMessageTypography(snapshot)
        scope.launch {
            try { pending.await(); if (revision == writeRevision) saveFailure = false
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { if (revision == writeRevision) saveFailure = true }
        }
    }
    fun edit(section: TypographyPreviewSection = TypographyPreviewSection.Body,
        transform: (MessageTypographySettings) -> MessageTypographySettings) {
        preview = section
        draft = transform(draft).copy(preset = MessageTypographyPreset.CUSTOM).normalized()
    }
    MiuixScaffoldPage(title = stringResource(R.string.message_typography_title), onBack = onBack,
        aboveContent = {
            WidePageContent(modifier = Modifier.height(previewHeight + 16.dp)) { sidePadding ->
                TypographyLivePreview(draft, preview, { preview = it }, saveFailure,
                    onReset = { save(MessageTypographySettings()) }, onRetry = { save(draft) },
                    height = previewHeight, compact = compactPreview, onToggleCompact = { compactPreview = !compactPreview },
                    modifier = Modifier.padding(horizontal = sidePadding + 16.dp, vertical = 8.dp))
            }
        }) {
        item(key = "message_preset") {
            NexusSettingsSmallTitle(text = stringResource(R.string.message_typography_style))
            NexusSettingsCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                NexusSettingsDropdownPreference(
                    title = stringResource(R.string.message_typography_preset),
                    items = listOf("标准", "紧凑", "舒展", "自定义"),
                    selectedIndex = draft.preset.ordinal,
                    onSelectedIndexChange = { index ->
                        val preset = MessageTypographyPreset.entries.getOrNull(index) ?: return@NexusSettingsDropdownPreference
                        if (preset != MessageTypographyPreset.CUSTOM) save(
                            MessageTypographySettings.forPreset(preset).copy(font = draft.font, renderMarkdown = draft.renderMarkdown))
                        else save(draft.copy(preset = preset))
                    },
                )
                NexusSettingsDropdownPreference(
                    title = stringResource(R.string.message_typography_font),
                    items = listOf("跟随界面", "系统字体", "人文无衬线", "书刊衬线", "等宽字体", "系统衬线"),
                    selectedIndex = draft.font.ordinal,
                    onSelectedIndexChange = { index -> MessageFont.entries.getOrNull(index)?.let { save(draft.copy(font = it)) } },
                )
                NexusSettingsSwitchPreference(
                    title = "显示排版结果",
                    summary = "关闭后显示原始文本；不会改变消息内容",
                    checked = draft.renderMarkdown,
                    onCheckedChange = { save(draft.copy(renderMarkdown = it)) },
                )
            }
        }
        item(key = "message_text") {
            NexusSettingsSmallTitle(text = "正文与思考")
            NexusSettingsCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                TypographySlider("正文字号", draft.bodySize, 12f..24f, { edit { s -> s.copy(bodySize = it) } }, { save(draft) })
                NexusSettingsDropdownPreference(title = "正文字重",
                    items = listOf("跟随原样式", "细体", "常规", "适中", "半粗", "粗体"),
                    selectedIndex = if (draft.bodyWeight == 0) 0 else ((draft.bodyWeight - 300) / 100 + 1).coerceIn(1, 5),
                    onSelectedIndexChange = {
                        preview = TypographyPreviewSection.Body
                        save(draft.copy(bodyWeight = if (it == 0) 0 else 200 + it * 100, preset = MessageTypographyPreset.CUSTOM))
                    })
                TypographySlider("正文字距", draft.bodyLetterSpacing, -.5f..3f, { edit { s -> s.copy(bodyLetterSpacing = it) } }, { save(draft) }, " sp", decimals = 1)
                TypographySlider("正文行高", draft.lineHeight, 16f..42f, { edit { s -> s.copy(lineHeight = it) } }, { save(draft) })
                TypographySlider("段落间距", draft.paragraphGapScale * 100, 50f..180f, { edit { s -> s.copy(paragraphGapScale = it / 100) } }, { save(draft) }, "%")
                TypographySlider("思考字号", draft.thinkingSize, 11f..22f, { edit(TypographyPreviewSection.Thinking) { s -> s.copy(thinkingSize = it) } }, { save(draft) })
                TypographySlider("思考行高", draft.thinkingLineHeight, 15f..36f, { edit(TypographyPreviewSection.Thinking) { s -> s.copy(thinkingLineHeight = it) } }, { save(draft) })
                TypographySlider("思考字距", draft.thinkingLetterSpacing, -.5f..3f, { edit(TypographyPreviewSection.Thinking) { s -> s.copy(thinkingLetterSpacing = it) } }, { save(draft) }, " sp", decimals = 1)
            }
        }
        item(key = "message_structure") {
            NexusSettingsSmallTitle(text = "标题、代码与表格")
            NexusSettingsCard(modifier = Modifier.padding(horizontal = 16.dp)) {
                TypographySlider("标题比例", draft.headingScale * 100, 90f..140f, { edit { s -> s.copy(headingScale = it / 100) } }, { save(draft) }, "%")
                NexusSettingsDropdownPreference(title = "标题字重", items = listOf("常规", "适中", "半粗", "粗体"),
                    selectedIndex = ((draft.headingWeight - 400) / 100).coerceIn(0, 3),
                    onSelectedIndexChange = { preview = TypographyPreviewSection.Body; save(draft.copy(headingWeight = 400 + it * 100, preset = MessageTypographyPreset.CUSTOM)) })
                TypographySlider("代码字号", draft.codeSize, 10f..22f, { edit(TypographyPreviewSection.Code) { s -> s.copy(codeSize = it) } }, { save(draft) })
                TypographySlider("代码行高", draft.codeLineHeightScale * 100, 120f..200f, { edit(TypographyPreviewSection.Code) { s -> s.copy(codeLineHeightScale = it / 100) } }, { save(draft) }, "%")
                NexusSettingsSwitchPreference(title = "代码自动换行", checked = draft.codeWrap,
                    onCheckedChange = { preview = TypographyPreviewSection.Code; save(draft.copy(codeWrap = it, preset = MessageTypographyPreset.CUSTOM)) })
                TypographySlider("表格字号", draft.tableSize, 11f..22f, { edit(TypographyPreviewSection.Table) { s -> s.copy(tableSize = it) } }, { save(draft) })
                TypographySlider("表格行距", draft.tableDensity * 100, 65f..140f, { edit(TypographyPreviewSection.Table) { s -> s.copy(tableDensity = it / 100) } }, { save(draft) }, "%")
            }
        }

    }
}

@Composable
private fun TypographySlider(title: String, value: Float, range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit, onCommit: () -> Unit, suffix: String = " sp", decimals: Int = 0) {
    SliderPreference(title = title, value = value.coerceIn(range), valueRange = range,
        onValueChange = onChange, onValueChangeFinished = onCommit,
        valueText = if (decimals == 0) "${kotlin.math.round(value).toInt()}$suffix"
            else String.format(java.util.Locale.getDefault(), "%.1f%s", value, suffix))
}

private enum class TypographyPreviewSection { Body, Thinking, Code, Table }

/** A page-local viewport in the scaffold's reserved area, never drawn over settings controls. */
@Composable
private fun TypographyLivePreview(
    draft: MessageTypographySettings,
    section: TypographyPreviewSection,
    onSection: (TypographyPreviewSection) -> Unit,
    saveFailure: Boolean,
    onReset: () -> Unit,
    onRetry: () -> Unit,
    height: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    onToggleCompact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appearance = LocalAppearanceSettings.current
    val scroll = rememberScrollState()
    LaunchedEffect(section) { scroll.scrollTo(0) }
    NexusSettingsCard(modifier = modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
        Column(Modifier.fillMaxWidth().height(height).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (saveFailure) "实时预览 · 尚未保存" else "实时预览", modifier = Modifier.weight(1f),
                    style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.footnote1, maxLines = 1)
                if (saveFailure) NexaCompactAction("重试", onClick = onRetry)
                NexaCompactAction(stringResource(R.string.message_typography_reset), onClick = onReset)
                NexaCompactAction("", onClick = onToggleCompact,
                    icon = if (compact) Icons.Rounded.UnfoldMore else Icons.Rounded.UnfoldLess,
                    modifier = Modifier.semantics { contentDescription = if (compact) "展开预览" else "紧凑预览" })
            }
            if (!compact) NexaSegmentedTabs(listOf("正文", "思考", "代码", "表格"), section.ordinal,
                { TypographyPreviewSection.entries.getOrNull(it)?.let(onSection) })
            // Scope typography to message content: tabs and actions retain the interface font.
            CompositionLocalProvider(LocalAppearanceSettings provides appearance.copy(messageTypography = draft)) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
                    SelectionContainer {
                        val sample = when (section) {
                            TypographyPreviewSection.Body -> BODY_SAMPLE
                            TypographyPreviewSection.Thinking -> THINKING_SAMPLE
                            TypographyPreviewSection.Code -> CODE_SAMPLE
                            TypographyPreviewSection.Table -> TABLE_SAMPLE
                        }
                        if (draft.renderMarkdown) StaticMarkdown(content = sample,
                            tone = if (section == TypographyPreviewSection.Thinking) MarkdownTone.Thinking else MarkdownTone.Answer)
                        else Text(sample, style = if (section == TypographyPreviewSection.Thinking)
                            io.github.mangi.eta.ui.markdown.rememberMarkdownStyle(MarkdownTone.Thinking).body
                            else messageBodyTextStyle())
                    }
                }
            }
        }
    }
}

private val BODY_SAMPLE = """
## 清晰的表达，从舒适的阅读开始
NEXA 可以按照你的偏好显示**正文、标题和代码**。这些调整只影响显示。

第二段用于预览段落间距与字距。长按文字可以选择复制。
""".trimIndent()
private val THINKING_SAMPLE = """
这是一段思考内容预览，用于单独调整字号、行高和字距。

第二段让你比较思考内容的阅读密度。
""".trimIndent()
private val CODE_SAMPLE = """
```kotlin
val message = "Hello, NEXA — 这是一条较长的代码行，用来观察自动换行效果"
println(message)
val result = message.uppercase()
```
""".trimIndent()
private val TABLE_SAMPLE = """
| 项目 | 状态 |
| --- | --- |
| 排版设置 | 即时预览 |
| 字号与行距 | 随调节更新 |
""".trimIndent()
