package io.github.mangi.eta.ui.components

import android.graphics.RuntimeShader
import io.github.mangi.eta.data.model.AppearanceSkin
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NexusGlassMaterialTest {
    @Test
    fun softwareLowRamAndDisabledEffectsAlwaysSelectStaticMaterial() {
        for (skin in AppearanceSkin.entries) {
            for (blur in listOf(false, true)) for (supported in listOf(false, true)) {
                for (hardware in listOf(false, true)) for (lowRam in listOf(false, true)) {
                    val policy = glassRenderPolicy(skin, blur, supported, hardware, lowRam)
                    val canSample = skin.isGlass && blur && supported && hardware && !lowRam
                    assertEquals(canSample, policy.sampleBackdrop)
                    assertEquals(canSample && skin == AppearanceSkin.LIQUID_GLASS, policy.refract)
                }
            }
        }
    }

    @Test
    fun disablingRefractionPreservesBackdropSamplingAndDefaultBehavior() {
        val disabled = glassRenderPolicy(
            AppearanceSkin.LIQUID_GLASS, true, true, true, false,
            refractionEnabled = false,
        )
        val default = glassRenderPolicy(AppearanceSkin.LIQUID_GLASS, true, true, true, false)

        assertEquals(true, disabled.sampleBackdrop)
        assertEquals(false, disabled.refract)
        assertEquals(true, default.sampleBackdrop)
        assertEquals(true, default.refract)
    }

    @Test
    fun lensShaderCompilesOnNativeSkiaAndAcceptsAllGeometryUniforms() {
        val shader = RuntimeShader(NexusLensShader)
        shader.setFloatUniform("extent", 240f, 96f)
        shader.setFloatUniform("inset", 24f)
        shader.setFloatUniform("radius", 32f)
        shader.setFloatUniform("rim", 14f)
        shader.setFloatUniform("bend", 5f)
    }
}
