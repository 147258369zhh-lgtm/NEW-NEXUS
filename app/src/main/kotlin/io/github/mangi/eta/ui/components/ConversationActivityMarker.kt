package io.github.mangi.eta.ui.components

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Immutable
internal data class ConversationActivityEnvironment(
    val today: LocalDate,
    val zone: ZoneId,
    val animateRunning: Boolean,
)

internal val LocalConversationActivityEnvironment = staticCompositionLocalOf {
    ConversationActivityEnvironment(LocalDate.now(), ZoneId.systemDefault(), animateRunning = false)
}

/** One set of event listeners for the pane, with no per-row timers or date polling. */
@Composable
internal fun rememberConversationActivityEnvironment(animationEnabled: Boolean): ConversationActivityEnvironment {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var calendar by remember {
        val zone = ZoneId.systemDefault()
        mutableStateOf(LocalDate.now(zone) to zone)
    }
    var systemAnimationEnabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(context, lifecycle) {
        fun refresh() {
            val zone = ZoneId.systemDefault()
            calendar = LocalDate.now(zone) to zone
            systemAnimationEnabled = ValueAnimator.areAnimatorsEnabled()
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        val animationObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        context.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animationObserver,
        )
        val lifecycleObserver = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed) refresh()
        }
        lifecycle.addObserver(lifecycleObserver)
        refresh()
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            context.unregisterReceiver(receiver)
            context.contentResolver.unregisterContentObserver(animationObserver)
        }
    }
    return remember(calendar, animationEnabled, systemAnimationEnabled, resumed) {
        ConversationActivityEnvironment(calendar.first, calendar.second, animationEnabled && systemAnimationEnabled && resumed)
    }
}

@Composable
internal fun ConversationActivityMarker(
    running: Boolean,
    activityAtMillis: Long,
    modifier: Modifier,
    dotRadius: Dp = 3.5.dp,
) {
    val environment = LocalConversationActivityEnvironment.current
    val ageInDays = remember(activityAtMillis, environment.today, environment.zone) {
        if (activityAtMillis <= 0L) null else {
            val activeDay = Instant.ofEpochMilli(activityAtMillis).atZone(environment.zone).toLocalDate()
            (environment.today.toEpochDay() - activeDay.toEpochDay()).coerceAtLeast(0L)
        }
    }
    val color = when {
        running -> Color(0xFF28B36F)
        ageInDays == 0L -> Color(0xFFF19A32)
        ageInDays == 1L || ageInDays == 2L -> Color(0xFFE75C65)
        else -> MiuixTheme.colorScheme.outline.copy(alpha = .62f)
    }
    val pulse: State<Float>? = if (running && environment.animateRunning) {
        rememberInfiniteTransition(label = "conversation activity").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_700, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "running glow",
        )
    } else null
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = dotRadius.toPx()
        if (running) {
            // Read the animation in draw, so only the visible active marker redraws each frame.
            val phase = pulse?.value ?: .35f
            val glowRadius = radius * (1.65f + phase * .25f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(color.copy(alpha = .10f + phase * .05f), Color.Transparent),
                    center = center,
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = center,
            )
        }
        drawCircle(color = color, radius = radius, center = center)
    }
}
