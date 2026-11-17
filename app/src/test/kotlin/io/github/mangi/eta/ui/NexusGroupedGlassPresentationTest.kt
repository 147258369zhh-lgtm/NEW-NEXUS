package io.github.mangi.eta.ui

import android.app.Application
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceSkin
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.screens.memory.MemoryLearningPresentationTest.Panel
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "zh-rCN-w320dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NexusGroupedGlassPresentationTest {
    @Test
    fun narrowLargeTextAppearanceKeepsFontAndEffectControlsReachableInBothGlassSkins() {
        for (skin in listOf(AppearanceSkin.FROST_GLASS, AppearanceSkin.LIQUID_GLASS)) {
            val appearance = AppearanceSettings(
                skin = skin,
                themeMode = if (skin == AppearanceSkin.FROST_GLASS) AppearanceThemeMode.LIGHT else AppearanceThemeMode.DARK,
                blurEnabled = false,
            )
            Panel(fontScale = 1.3f) {
                AgentAppTheme(appearance, applyInterfaceScale = true) { AppearanceSettingsScreen(onBack = {}) }
            }.use { panel ->
                panel.await { panel.texts().contains("界面皮肤") }
                panel.capture("${skin.persistedValue}-appearance-theme")
                panel.click("字体与显示")
                panel.await { panel.texts().contains("界面缩放") }
                panel.capture("${skin.persistedValue}-appearance-display")
                panel.click("效果与手势")
                panel.await { panel.texts().contains("模糊效果") }
                panel.scrollUntil("预测性返回手势")
                assertTrue(panel.texts().contains("预测性返回手势"))
                panel.capture("${skin.persistedValue}-appearance-effects")
            }
        }
    }
}
