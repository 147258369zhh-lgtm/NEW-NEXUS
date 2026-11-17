package io.github.mangi.eta.ui.screens.capability

import android.app.Application
import io.github.mangi.eta.agent.packages.PackageAiProviderTest
import io.github.mangi.eta.agent.packages.PackageAiRegistry
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.screens.memory.MemoryLearningPresentationTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w320dp-h840dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PackageAiProviderPresentationTest {
    @After fun close() { io.github.mangi.eta.data.db.EtaDatabase.closeForTests() }
    @Test fun existingPackageDetailsExposeProviderConnectionAndPreserveTheOrdinaryTabs() = runBlocking {
        val fixture = PackageAiProviderTest(); fixture.setup()
        val (declaration, provider) = fixture.install()
        ProviderRepository.deleteProvider(provider.id)
        MemoryLearningPresentationTest.Panel(dark = true, fontScale = 1.3f) { PackageCenterScreen(onBack = {}) }.use { panel ->
            panel.await { panel.texts().contains(declaration.record.title) }
            panel.click(declaration.record.title)
            panel.await { panel.texts().contains("接入模型") }
            panel.click("插件模型")
            panel.await { panel.texts().contains("已接入") }
            panel.capture("package-ai-provider-connection-narrow-dark")
            assertTrue(PackageAiRegistry(RuntimeEnvironment.getApplication()).available(provider.id))
            assertTrue(panel.texts().containsAll(listOf("概览", "权限", "运行", "配置", "扩展")))
        }
    }
}
