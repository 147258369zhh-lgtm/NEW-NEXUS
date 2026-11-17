package io.github.mangi.eta.ui.components

import android.app.ActivityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.mangi.eta.data.model.AppearanceSkin
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.app.LocalBlurEnabled
import io.github.mangi.eta.ui.app.LocalEtaDesignTokens
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.runtimeShaderEffect
import top.yukonga.miuix.kmp.blur.progressiveTextureBlurEffect
import top.yukonga.miuix.kmp.blur.textureBlurEffect

/** The scrolling body is captured once; cards never capture their own descendants. */
internal val LocalNexusBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

internal data class GlassRenderPolicy(val sampleBackdrop: Boolean, val refract: Boolean)

internal fun glassRenderPolicy(
    skin: AppearanceSkin,
    blurEnabled: Boolean,
    shaderSupported: Boolean,
    hardwareAccelerated: Boolean,
    lowRamDevice: Boolean,
    refractionEnabled: Boolean = true,
): GlassRenderPolicy {
    val sample = skin.isGlass && blurEnabled && shaderSupported && hardwareAccelerated && !lowRamDevice
    return GlassRenderPolicy(sample, sample && skin == AppearanceSkin.LIQUID_GLASS && refractionEnabled)
}

@Composable
internal fun rememberGlassRenderPolicy(): GlassRenderPolicy {
    val context = LocalContext.current
    val lowRam = remember(context.applicationContext) {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    }
    return glassRenderPolicy(
        LocalEtaDesignTokens.current.skin, LocalBlurEnabled.current,
        isRuntimeShaderSupported(), LocalView.current.isHardwareAccelerated, lowRam,
        refractionEnabled = LocalAppearanceSettings.current.glassRefractionEnabled,
    )
}

/**
 * Background only: callers draw text/icons in a sibling above this layer. A software canvas
 * bypasses the entire GPU modifier, even when the hosting View normally uses acceleration.
 * No clock, full-screen lens, separate capture or per-card render effect is introduced.
 */
@Composable
internal fun NexusGlassBackdrop(
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = LocalNexusBackdrop.current,
    cornerRadius: Dp = 20.dp,
    selected: Boolean = false,
    refract: Boolean = true,
    progressive: Boolean = false,
) {
    if (LocalNexusSettingsVisuals.current) {
        Box(modifier.background(settingsCardColor(), RoundedCornerShape(cornerRadius)))
        return
    }
    val tokens = LocalEtaDesignTokens.current
    val policy = rememberGlassRenderPolicy()
    val tint = tokens.surface.copy(alpha = if (selected) tokens.glassAlpha * 0.65f else tokens.glassAlpha)
    val accent = tokens.accent.copy(alpha = if (selected) 0.10f else 0.025f)
    val shape = RoundedCornerShape(cornerRadius)
    val material = if (policy.sampleBackdrop && backdrop != null) {
        Modifier.drawWithContent {
            if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                drawContent()
            } else {
                drawRoundRect(tokens.surface.copy(alpha = 0.94f), cornerRadius = CornerRadius(cornerRadius.toPx()))
            }
        }.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                // The Miuix texture API accepts dp-valued floats and performs density conversion.
                val blur = tokens.blurRadius.value
                val colors = BlurColors(blendColors = listOf(BlendColorEntry(tint)))
                if (progressive) progressiveTextureBlurEffect(
                    blurRadiusX = blur, blurRadiusY = blur, gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
                    noiseCoefficient = 0.025f, colors = colors,
                ) else textureBlurEffect(
                    blurRadiusX = blur, blurRadiusY = blur, noiseCoefficient = 0.025f, colors = colors,
                )
                if (policy.refract && refract) {
                    // RuntimeShader is cached by the existing backdrop owner. Coordinates include
                    // blur padding; only the narrow inner rim is displaced, never foreground text.
                    val width = size.width
                    val height = size.height
                    val radius = cornerRadius.toPx().coerceAtMost(minOf(width, height) / 2f)
                    val inset = padding
                    val rim = 7.dp.toPx()
                    val bend = 2.5.dp.toPx()
                    runtimeShaderEffect("nexus-lens-v1", NexusLensShader, "source") {
                        setFloatUniform("extent", width, height)
                        setFloatUniform("inset", inset)
                        setFloatUniform("radius", radius)
                        setFloatUniform("rim", rim)
                        setFloatUniform("bend", bend)
                    }
                }
            },
            onDrawSurface = {
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), accent)))
            },
        )
    } else {
        Modifier.background(
            Brush.verticalGradient(listOf(tokens.surface.copy(alpha = 0.94f), tint, tokens.surface.copy(alpha = 0.90f))),
            shape,
        )
    }
    Box(modifier.then(material))
}

// Original rounded-rectangle lens: bounded displacement, no chromatic fringing or animated noise.
internal const val NexusLensShader = """
uniform shader source;
uniform float2 extent;
uniform float inset;
uniform float radius;
uniform float rim;
uniform float bend;
half4 main(float2 p) {
    float2 uv = p - float2(inset);
    float2 halfSize = extent * 0.5;
    float2 centered = uv - halfSize;
    float2 q = abs(centered) - (halfSize - float2(radius));
    float2 outside = max(q, float2(0.0));
    float sd = length(outside) + min(max(q.x, q.y), 0.0) - radius;
    float2 axis = q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0);
    float2 normal = length(outside) > 0.001 ? normalize(outside) : axis;
    normal *= sign(centered);
    float edge = 1.0 - smoothstep(0.0, rim, abs(sd));
    float2 samplePoint = clamp(uv - normal * edge * bend, float2(0.0), extent);
    return source.eval(samplePoint + float2(inset));
}
"""
