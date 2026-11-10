package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.RecordVoiceOver
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.EtaStatusPill
import io.github.mangi.eta.ui.components.EtaStatusTone
import io.github.mangi.eta.ui.components.PreferenceIcon
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 语音设置总览：识别与播报各有独立子页面（预测性返回与滑动返回由 miuix-nav 提供），
 * 这里的开关直接读写持久化配置，不涉及凭据草稿。
 */
@Composable
internal fun SpeechSettingsScreen(onBack: () -> Unit, onNavigate: (AppRoute) -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by SpeechSettingsRepository.settingsFlow().collectAsState(initial = null)
    val current = settings

    MiuixScaffoldPage(
        title = stringResource(R.string.speech_settings_title),
        onBack = onBack,
    ) {
        if (current == null) {
            item(key = "loading") { SpeechLoadingState(failure = null) }
            return@MiuixScaffoldPage
        }
        item(key = "overview") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    EtaIconTile(Icons.Rounded.GraphicEq, tone = EtaIconTone.SYSTEM, size = 40.dp, glyphSize = 21.dp)
                    Column(Modifier.weight(1f), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp)) {
                        Text("语音", style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.title3, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                !current.recognitionEnabled -> "语音识别已关闭"
                                current.autoSpeak && current.tts != TtsProvider.NONE -> "连续对话已启用"
                                else -> current.asr.label()
                            },
                            style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.footnote1,
                            color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    EtaStatusPill(
                        if (current.recognitionEnabled) "识别开启" else "识别关闭",
                        tone = if (current.recognitionEnabled) EtaStatusTone.Positive else EtaStatusTone.Neutral,
                    )
                }
            }
        }
        item(key = "service") {
            SmallTitle(stringResource(R.string.speech_group_service))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.speech_recognition_title),
                    summary = if (current.recognitionEnabled) current.asr.label()
                        else stringResource(R.string.speech_recognition_disabled),
                    startAction = { PreferenceIcon(Icons.Rounded.RecordVoiceOver) },
                    onClick = { onNavigate(AppRoute.SpeechRecognition) },
                )
                ArrowPreference(
                    title = stringResource(R.string.speech_synthesis_title),
                    summary = current.tts.label(),
                    startAction = { PreferenceIcon(Icons.AutoMirrored.Rounded.VolumeUp) },
                    onClick = { onNavigate(AppRoute.SpeechSynthesis) },
                )
            }
        }
        item(key = "assistant") {
            Column {
                SmallTitle(stringResource(R.string.speech_group_assistant))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.speech_auto_speak),
                        summary = stringResource(R.string.speech_auto_speak_summary),
                        checked = current.autoSpeak,
                        enabled = current.tts != TtsProvider.NONE,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                SpeechSettingsRepository.save(current.copy(autoSpeak = enabled))
                            }
                        },
                    )
                }
            }
        }
    }
}
