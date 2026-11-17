package io.github.mangi.eta.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceAccentColor
import io.github.mangi.eta.data.model.AppearanceFontFamily
import io.github.mangi.eta.data.model.AppearancePaletteStyle
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceSkin
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.model.AppearanceTopBarBlurStyle
import io.github.mangi.eta.data.model.MAX_INTERFACE_SCALE
import io.github.mangi.eta.data.model.MIN_INTERFACE_SCALE
import io.github.mangi.eta.data.model.normalizeInterfaceScale
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.NexusSettingsTabs
import io.github.mangi.eta.ui.components.NexaSegmentedTabs
import io.github.mangi.eta.ui.components.NexaCompactAction
import io.github.mangi.eta.ui.components.LanguagePreference
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
import top.yukonga.miuix.kmp.basic.Text
import io.github.mangi.eta.ui.components.etaGlassSurface
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import io.github.mangi.eta.ui.components.NexusSettingsCard as Card
import top.yukonga.miuix.kmp.basic.SliderDefaults
import io.github.mangi.eta.ui.components.NexusSettingsSmallTitle as SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import io.github.mangi.eta.ui.components.NexusSettingsDropdownPreference as OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import io.github.mangi.eta.ui.components.NexusSettingsSwitchPreference as SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AppearanceSettingsScreen(onBack: () -> Unit, onOpenMessageTypography: () -> Unit = {}) {
    val appearance = LocalAppearanceSettings.current
    val context = LocalContext.current
    val saveFailedMessage = stringResource(R.string.appearance_save_failed)
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val themeListState = rememberLazyListState()
    val displayListState = rememberLazyListState()
    val effectsListState = rememberLazyListState()
    var scaleDraft by remember(appearance.interfaceScale) {
        mutableFloatStateOf(appearance.interfaceScale * 100f)
    }
    var showScaleDialog by remember { mutableStateOf(false) }
    var opacityDraft by remember(appearance.glassOpacityMultiplier) { mutableFloatStateOf(appearance.glassOpacityMultiplier * 100f) }
    var blurDraft by remember(appearance.glassBlurMultiplier) { mutableFloatStateOf(appearance.glassBlurMultiplier * 100f) }
    var swipeDraft by remember(appearance.homeModeSwipeThresholdDp) { mutableFloatStateOf(appearance.homeModeSwipeThresholdDp) }
    var scaleInput by remember { mutableStateOf("") }
    val blurSupported = isRuntimeShaderSupported()

    fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        val write = AppearanceSettingsRepository.saveAppearanceChange(transform)
        coroutineScope.launch {
            try { write.await() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { Toast.makeText(context, saveFailedMessage, Toast.LENGTH_SHORT).show() }
        }
    }

    fun commitScale(percent: Float) {
        val scale = normalizeInterfaceScale(percent.roundToInt() / 100f)
        scaleDraft = scale * 100f
        update { current -> current.copy(interfaceScale = scale) }
    }

    val themeModes = AppearanceThemeMode.entries
    val skins = listOf(AppearanceSkin.CLASSIC, AppearanceSkin.LIQUID_GLASS)
    val skinLabels = listOf(
        stringResource(R.string.appearance_skin_classic),
        stringResource(R.string.appearance_skin_liquid_glass),
    )
    val themeModeLabels = listOf(
        stringResource(R.string.appearance_theme_system),
        stringResource(R.string.appearance_theme_light),
        stringResource(R.string.appearance_theme_dark),
    )
    val paletteStyles = AppearancePaletteStyle.entries
    val paletteLabels = listOf(
        stringResource(R.string.appearance_palette_tonal_spot),
        stringResource(R.string.appearance_palette_neutral),
        stringResource(R.string.appearance_palette_vibrant),
        stringResource(R.string.appearance_palette_expressive),
        stringResource(R.string.appearance_palette_rainbow),
        stringResource(R.string.appearance_palette_fruit_salad),
        stringResource(R.string.appearance_palette_monochrome),
        stringResource(R.string.appearance_palette_fidelity),
        stringResource(R.string.appearance_palette_content),
    )
    val accentColors = AppearanceAccentColor.entries
    val accentLabels = listOf(
        stringResource(R.string.appearance_accent_system),
        stringResource(R.string.appearance_accent_blue),
        stringResource(R.string.appearance_accent_purple),
        stringResource(R.string.appearance_accent_pink),
        stringResource(R.string.appearance_accent_red),
        stringResource(R.string.appearance_accent_orange),
        stringResource(R.string.appearance_accent_yellow),
        stringResource(R.string.appearance_accent_green),
        stringResource(R.string.appearance_accent_teal),
    )
    val blurStyles = AppearanceTopBarBlurStyle.entries
    val blurStyleLabels = listOf(
        stringResource(R.string.appearance_blur_style_gaussian),
        stringResource(R.string.appearance_blur_style_progressive),
    )
    val blurStyleSummaries = listOf(
        stringResource(R.string.appearance_blur_style_gaussian_summary),
        stringResource(R.string.appearance_blur_style_progressive_summary),
    )
    val fontFamilies = AppearanceFontFamily.entries
    val fontFamilyLabels = listOf(
        stringResource(R.string.appearance_font_system),
        stringResource(R.string.appearance_font_humanist),
        stringResource(R.string.appearance_font_editorial),
    )

    MiuixScaffoldPage(
        title = stringResource(R.string.appearance_title),
        onBack = onBack,
        listState = listOf(themeListState, displayListState, effectsListState)[selectedTab],
        aboveContent = {
            NexaSegmentedTabs(
                tabs = listOf(
                    stringResource(R.string.settings_tab_theme),
                    stringResource(R.string.settings_tab_display),
                    stringResource(R.string.settings_tab_effects_gestures),
                ),
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        },
    ) {
        if (selectedTab == 0) item(key = "appearance_language") {
            SmallTitle(stringResource(R.string.settings_language))
            Card(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                LanguagePreference(startAction = {
                    EtaIconTile(Icons.Rounded.Language, tone = EtaIconTone.MODEL, size = 24.dp, glyphSize = 24.dp)
                })
            }
        }
        if (selectedTab == 0) item(key = "appearance_color") {
            SmallTitle(text = stringResource(R.string.appearance_group_color))
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp).etaGlassSurface(),
            ) {
                OverlayDropdownPreference(
                    title = stringResource(R.string.appearance_skin),
                    items = skinLabels,
                    selectedIndex = skins.indexOf(appearance.skin).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        skins.getOrNull(index)?.let { skin ->
                            update { current -> current.copy(skin = skin) }
                        }
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.appearance_theme_mode),
                    items = themeModeLabels,
                    selectedIndex = appearance.themeMode.ordinal,
                    onSelectedIndexChange = { index ->
                        themeModes.getOrNull(index)?.let { mode ->
                            update { current -> current.copy(themeMode = mode) }
                        }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.appearance_monet),
                    checked = appearance.monetEnabled,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(monetEnabled = enabled) }
                    },
                )
                AnimatedVisibility(
                    visible = appearance.monetEnabled,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Column {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.appearance_palette_style),
                            items = paletteLabels,
                            selectedIndex = appearance.paletteStyle.ordinal,
                            onSelectedIndexChange = { index ->
                                paletteStyles.getOrNull(index)?.let { style ->
                                    update { current -> current.copy(paletteStyle = style) }
                                }
                            },
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.appearance_accent_color),
                            items = accentLabels,
                            selectedIndex = appearance.accentColor.ordinal,
                            onSelectedIndexChange = { index ->
                                accentColors.getOrNull(index)?.let { accent ->
                                    update { current -> current.copy(accentColor = accent) }
                                }
                            },
                        )
                    }
                }
                SwitchPreference(
                    title = stringResource(R.string.appearance_pure_black),
                    checked = appearance.pureBlackEnabled,
                    onCheckedChange = { enabled -> update { it.copy(pureBlackEnabled = enabled) } },
                )
            }
        }
        if (selectedTab == 1) item(key = "appearance_interface") {
            SmallTitle(text = stringResource(R.string.appearance_group_interface))
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp).etaGlassSurface(),
            ) {
                io.github.mangi.eta.ui.components.NexusSettingsArrowPreference(
                    title = stringResource(R.string.message_typography_title),
                    onClick = onOpenMessageTypography,
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.appearance_font_family),
                    items = fontFamilyLabels,
                    selectedIndex = appearance.fontFamily.ordinal,
                    onSelectedIndexChange = { index ->
                        fontFamilies.getOrNull(index)?.let { family ->
                            update { current -> current.copy(fontFamily = family) }
                        }
                    },
                )
                SliderPreference(
                    title = stringResource(R.string.appearance_interface_scale),
                    summary = stringResource(R.string.appearance_interface_scale_summary),
                    value = scaleDraft.coerceIn(
                        MIN_INTERFACE_SCALE * 100f,
                        MAX_INTERFACE_SCALE * 100f,
                    ),
                    onValueChange = { scaleDraft = it },
                    valueRange = (MIN_INTERFACE_SCALE * 100f)..(MAX_INTERFACE_SCALE * 100f),
                    onValueChangeFinished = { commitScale(scaleDraft) },
                    valueText = "${scaleDraft.roundToInt()}%",
                    showKeyPoints = true,
                    keyPoints = listOf(80f, 90f, 100f, 110f),
                    magnetThreshold = 0.01f,
                    hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                    onClick = {
                        scaleInput = scaleDraft.roundToInt().toString()
                        showScaleDialog = true
                    },
                    holdDownState = showScaleDialog,
                )
            }
        }
        if (selectedTab == 2) item(key = "appearance_effects") {
            SmallTitle(text = stringResource(R.string.appearance_group_effects))
            Card(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 12.dp).etaGlassSurface(),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.appearance_blur),
                    checked = appearance.blurEnabled && blurSupported,
                    onCheckedChange = { enabled ->
                        update { current -> current.copy(blurEnabled = enabled) }
                    },
                    enabled = blurSupported,
                )
                AnimatedVisibility(
                    visible = appearance.blurEnabled && blurSupported,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.appearance_blur_style),
                        summary = blurStyleSummaries[appearance.topBarBlurStyle.ordinal],
                        items = blurStyleLabels,
                        selectedIndex = appearance.topBarBlurStyle.ordinal,
                        onSelectedIndexChange = { index ->
                            blurStyles.getOrNull(index)?.let { style ->
                                update { current -> current.copy(topBarBlurStyle = style) }
                            }
                        },
                    )
                }
                SwitchPreference(title = stringResource(R.string.appearance_press_feedback),
                    checked = appearance.pressFeedbackEnabled,
                    onCheckedChange = { enabled -> update { it.copy(pressFeedbackEnabled = enabled) } })
            }
        }
        if (selectedTab == 2 && appearance.skin.isGlass) item(key = "appearance_glass_controls") {
            SmallTitle(stringResource(R.string.appearance_glass_group))
            Card(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                SliderPreference(title = stringResource(R.string.appearance_glass_density),
                    value = opacityDraft, valueRange = 65f..125f, onValueChange = { opacityDraft = it },
                    onValueChangeFinished = { update { it.copy(glassOpacityMultiplier = opacityDraft / 100f) } },
                    valueText = "${opacityDraft.roundToInt()}%")
                SliderPreference(title = stringResource(R.string.appearance_glass_softness),
                    enabled = appearance.blurEnabled && blurSupported,
                    value = blurDraft, valueRange = 50f..200f, onValueChange = { blurDraft = it },
                    onValueChangeFinished = { update { it.copy(glassBlurMultiplier = blurDraft / 100f) } },
                    valueText = "${blurDraft.roundToInt()}%")
                SwitchPreference(title = stringResource(R.string.appearance_glass_refraction),
                    checked = appearance.glassRefractionEnabled, enabled = appearance.blurEnabled && blurSupported,
                    onCheckedChange = { enabled -> update { it.copy(glassRefractionEnabled = enabled) } })
            }
        }
        if (selectedTab == 2) item(key = "appearance_gesture_controls") {
            SmallTitle(stringResource(R.string.appearance_gesture_group))
            Card(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
                SwitchPreference(title = stringResource(R.string.appearance_swipe_dismiss),
                    checked = appearance.swipeDismissEnabled,
                    onCheckedChange = { enabled -> update { it.copy(swipeDismissEnabled = enabled) } })
                SwitchPreference(title = stringResource(R.string.appearance_predictive_back),
                    checked = appearance.predictiveBackEnabled,
                    onCheckedChange = { enabled -> update { it.copy(predictiveBackEnabled = enabled) } })
                SwitchPreference(title = stringResource(R.string.appearance_mode_swipe),
                    checked = appearance.homeModeSwipeEnabled,
                    onCheckedChange = { enabled -> update { it.copy(homeModeSwipeEnabled = enabled) } })
                SliderPreference(title = stringResource(R.string.appearance_mode_swipe_distance),
                    enabled = appearance.homeModeSwipeEnabled,
                    value = swipeDraft, valueRange = 32f..96f, onValueChange = { swipeDraft = it },
                    onValueChangeFinished = { update { it.copy(homeModeSwipeThresholdDp = swipeDraft) } },
                    valueText = "${swipeDraft.roundToInt()}")
            }
        }
    }

    val parsedScale = scaleInput.toIntOrNull()
    WindowDialog(
        show = showScaleDialog,
        title = stringResource(R.string.appearance_interface_scale_dialog_title),
        summary = stringResource(R.string.appearance_interface_scale_dialog_summary),
        onDismissRequest = { showScaleDialog = false },
    ) {
        Column {
            TextField(
                value = scaleInput,
                onValueChange = { value -> scaleInput = value.filter(Char::isDigit).take(3) },
                label = stringResource(R.string.appearance_interface_scale_input_label),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_confirm),
                confirmEnabled = parsedScale != null && parsedScale in 80..110,
                onCancel = { showScaleDialog = false },
                onConfirm = {
                    parsedScale?.let { commitScale(it.toFloat()) }
                    showScaleDialog = false
                },
            )
        }
    }
}
