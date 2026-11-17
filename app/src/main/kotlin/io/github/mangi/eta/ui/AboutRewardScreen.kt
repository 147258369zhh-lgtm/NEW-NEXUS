package io.github.mangi.eta.ui

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.RewardImageSaver
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.NexusSettingsArrowPreference
import io.github.mangi.eta.ui.components.NexusSettingsGroup
import io.github.mangi.eta.ui.components.NexusSettingsSectionTitle
import io.github.mangi.eta.ui.components.etaGlassSurface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/** Local page keeps the settings scroll position and requires no root navigation changes. */
@Composable
internal fun AboutRewardScreen(
    version: String,
    checkingUpdate: Boolean,
    onCheckUpdate: () -> Unit,
    onFeedback: () -> Unit,
    onQqGroup: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    MiuixScaffoldPage(title = stringResource(R.string.ui_about_bed172), onBack = onBack) {
        item(key = "about_details") {
            NexusSettingsGroup {
                BasicComponent(title = stringResource(R.string.ui_about_version_title), summary = version,
                    startAction = { EtaIconTile(Icons.Rounded.Info, accentColor = Color(0xFF3478F6)) })
                NexusSettingsArrowPreference(
                    title = stringResource(R.string.ui_about_update_title),
                    summary = if (checkingUpdate) stringResource(R.string.ui_about_update_checking) else null,
                    enabled = !checkingUpdate, onClick = onCheckUpdate,
                    startAction = { EtaIconTile(Icons.Rounded.SystemUpdate, accentColor = Color(0xFF26AD60)) },
                )
                NexusSettingsArrowPreference(
                    title = stringResource(R.string.ui_about_feedback_title), onClick = onFeedback,
                    startAction = { EtaIconTile(Icons.Rounded.BugReport, accentColor = Color(0xFFF28A26)) },
                )
                NexusSettingsArrowPreference(
                    title = stringResource(R.string.ui_about_join_qq_group),
                    summary = stringResource(R.string.ui_about_qq_group_number, "1107693849"), onClick = onQqGroup,
                    startAction = { EtaIconTile(Icons.Rounded.Forum, accentColor = Color(0xFF3478F6)) },
                )
            }
        }
        item(key = "about_reward") {
            NexusSettingsSectionTitle(stringResource(R.string.about_reward_title))
            RewardCard()
        }
    }
}

@Composable
private fun RewardCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val skin = LocalEtaDesignTokens.current.skin
    val colors = MiuixTheme.colorScheme
    val density = LocalDensity.current
    var angle by remember { mutableFloatStateOf(0f) }
    var snapJob by remember { mutableStateOf<Job?>(null) }
    var saving by remember { mutableStateOf(false) }
    var animationsEnabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { animationsEnabled = ValueAnimator.areAnimatorsEnabled() }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer); snapJob?.cancel() }
    }
    val normalized = ((angle % 360f) + 360f) % 360f
    val back = normalized > 90f && normalized < 270f
    val faceLabel = stringResource(if (back) R.string.about_reward_alipay else R.string.about_reward_wechat)
    val saveLabel = stringResource(R.string.about_reward_save)
    val flipLabel = stringResource(R.string.about_reward_flip)
    fun settle(target: Float) {
        snapJob?.cancel()
        snapJob = scope.launch {
            if (animationsEnabled) animate(angle, target, animationSpec = tween(240)) { value, _ -> angle = value }
            else angle = target
            angle = ((target % 360f) + 360f) % 360f
        }
    }
    fun saveFace() {
        if (saving) return
        // Capture the current face before switching or asynchronous work can change it.
        val resource = if (back) R.drawable.alipay_reward_qr else R.drawable.wechat_reward_qr
        val name = if (back) "NEXA-Alipay-${System.currentTimeMillis()}.jpg" else "NEXA-WeChat-${System.currentTimeMillis()}.png"
        val mime = if (back) "image/jpeg" else "image/png"
        saving = true
        scope.launch {
            try {
                RewardImageSaver.save(context.applicationContext, resource, name, mime)
                Toast.makeText(context, R.string.about_reward_saved, Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Toast.makeText(context, R.string.about_reward_save_failed, Toast.LENGTH_LONG).show()
            } finally { saving = false }
        }
    }
    val saveCurrentFace by rememberUpdatedState<() -> Unit>({ saveFace() })
    val settleCurrent by rememberUpdatedState<(Float) -> Unit>({ settle(it) })
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            EtaIconTile(Icons.Rounded.Favorite, accentColor = if (back) Color(0xFF1677FF) else Color(0xFF07C160))
            Text(faceLabel, style = MiuixTheme.textStyles.title3)
        }
        Text(stringResource(R.string.about_reward_thanks), style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary)
        BoxWithConstraints(
            Modifier.widthIn(max = 320.dp).fillMaxWidth().height(350.dp)
                .semantics {
                    stateDescription = faceLabel
                    customActions = listOf(CustomAccessibilityAction(saveLabel) { saveFace(); true },
                        CustomAccessibilityAction(flipLabel) { settle(round(angle / 180f) * 180f + 180f); true })
                }
                .pointerInput(Unit) { detectTapGestures(onLongPress = { saveCurrentFace() }) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { snapJob?.cancel() },
                        onDragEnd = { settleCurrent(round(angle / 180f) * 180f) },
                        onDragCancel = { settleCurrent(round(angle / 180f) * 180f) },
                    ) { change, delta ->
                        change.consume()
                        angle += delta / size.width.coerceAtLeast(1) * 360f
                    }
                }, contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(22.dp)
            val radians = Math.toRadians(angle.toDouble())
            val widthPx = with(density) { maxWidth.toPx() }
            // A narrow, independently rotated edge gives the two-sided card physical thickness.
            Box(Modifier.width(4.dp).height(330.dp).graphicsLayer {
                rotationY = angle - 90f
                translationX = (cos(radians) * widthPx * 0.5).toFloat()
                cameraDistance = 12f * density.density
            }.background(Brush.horizontalGradient(listOf(Color(0xFFADB5BE), Color(0xFFEFF2F5), Color(0xFFABB3BC)))))
            Box(Modifier.fillMaxSize().padding(vertical = 10.dp).graphicsLayer {
                rotationY = angle
                cameraDistance = 12f * density.density
                translationX = (sin(radians) * 2f * density.density).toFloat()
            }.shadow(if (skin.isGlass) 9.dp else 5.dp, shape)
                .etaGlassSurface(cornerRadius = 22.dp)
                .clip(shape).background(if (skin.isGlass) colors.surfaceContainer.copy(alpha = 0.9f) else colors.surfaceContainer)
                .padding(12.dp)) {
                // The rear has its own 180° orientation so its image never appears mirrored.
                Box(Modifier.fillMaxSize().graphicsLayer { rotationY = if (back) 180f else 0f }
                    .clip(RoundedCornerShape(10.dp)).background(Color.White).padding(8.dp)) {
                    Image(painterResource(if (back) R.drawable.alipay_reward_display else R.drawable.wechat_reward_display),
                        contentDescription = faceLabel, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
        }
        Text(stringResource(R.string.about_reward_gesture), style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(flipLabel, onClick = { settle(round(angle / 180f) * 180f + 180f) })
            TextButton(if (saving) stringResource(R.string.about_reward_saving) else saveLabel,
                enabled = !saving, onClick = { saveFace() })
        }
    }
}

