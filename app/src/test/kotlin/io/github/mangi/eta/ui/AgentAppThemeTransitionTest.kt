package io.github.mangi.eta.ui

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.screens.memory.MemoryLearningPresentationTest.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "zh-rCN-w320dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentAppThemeTransitionTest {
    @Test fun classicPaletteTracksDarkLightAndSystemChanges() {
        var mode by mutableStateOf(AppearanceThemeMode.LIGHT)
        var isDark = false
        var surface: Color? = null
        var background: Color? = null
        Panel {
            AgentAppTheme(AppearanceSettings(themeMode = mode, blurEnabled = false), applyInterfaceScale = false,
                onResolvedDarkModeChange = { isDark = it }) {
                val colors = MiuixTheme.colorScheme
                val currentSurface = colors.surface
                val currentBackground = colors.background
                SideEffect { surface = currentSurface; background = currentBackground }
            }
        }.use { panel ->
            panel.await { surface == Color.White && background == Color(0xFFF0F1F3) }
            mode = AppearanceThemeMode.DARK
            panel.await { isDark && surface != null && surface != Color.White && background != Color(0xFFF0F1F3) }
            val darkSurface = surface
            val darkBackground = background
            mode = AppearanceThemeMode.LIGHT
            panel.await { !isDark && surface == Color.White && background == Color(0xFFF0F1F3) }
            assertNotEquals(darkSurface, surface)
            assertNotEquals(darkBackground, background)
            mode = AppearanceThemeMode.SYSTEM
            panel.await { !isDark && surface == Color.White }
            assertEquals(Color(0xFFF0F1F3), background)
        }
    }
}
