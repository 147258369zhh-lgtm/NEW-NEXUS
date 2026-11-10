package io.github.mangi.eta.ui.screens.prompt

import android.app.Application
import io.github.mangi.eta.agent.prompt.PromptBlock
import io.github.mangi.eta.agent.prompt.PromptInjectionConfig
import io.github.mangi.eta.agent.prompt.PromptInjectionStore
import io.github.mangi.eta.agent.prompt.PromptScopeBinding
import io.github.mangi.eta.agent.prompt.PromptScopeType
import io.github.mangi.eta.agent.prompt.PromptTarget
import io.github.mangi.eta.agent.prompt.PromptTemplate
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.ui.screens.memory.MemoryLearningPresentationTest.Panel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "zh-rCN-w320dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PromptInjectionPresentationTest {
    @Test
    fun libraryAndEditorStayReadableOnNarrowDarkScreen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        val template = PromptTemplate(
            name = "周报核对",
            description = "生成周报前先核对引用来源，并在结尾列出仍然缺失的信息。",
            blocks = listOf(
                PromptBlock(
                    title = "核对步骤",
                    content = "先列出结论与来源的对应关系，发现缺失的来源时明确标注，再继续生成周报。",
                    order = 10,
                ),
            ),
        )
        PromptInjectionStore.replace(
            PromptInjectionConfig(
                templates = listOf(template),
                bindings = listOf(PromptScopeBinding(templateId = template.id, target = PromptTarget.GLOBAL)),
            ),
        )
        Panel(dark = true, fontScale = 1.3f) { PromptInjectionScreen {} }.use { panel ->
            panel.await { panel.texts().contains("周报核对") }
            assertTrue(panel.texts().contains("模板库"))
            assertTrue(panel.texts().contains("作用域"))
            assertTrue(panel.texts().contains("迁移"))
            assertTrue(panel.texts().any { it.contains("绑定 1") || it.contains("1 个范围") })
            panel.capture("prompt-library-narrow-dark")

            panel.click("周报核对")
            panel.await { panel.texts().contains("模板信息") }
            panel.capture("prompt-editor-narrow-dark")
            panel.scrollUntil("提示词块")
            panel.scrollUntil("第 1 块")
            assertTrue(panel.texts().contains("第 1 块"))
            assertTrue(panel.texts().contains("插入位置"))
            panel.capture("prompt-editor-blocks-narrow-dark")

            panel.back()
            panel.await { panel.texts().contains("周报核对") }
        }
    }

    @Test
    fun globalBindingReturnsToTheBindingsOverview() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        val template = PromptTemplate(
            name = "全局测试",
            blocks = listOf(PromptBlock(title = "规则", content = "保持简洁", order = 10)),
        )
        PromptInjectionStore.replace(PromptInjectionConfig(templates = listOf(template)))

        Panel(dark = false, fontScale = 1.3f) { PromptInjectionScreen {} }.use { panel ->
            panel.await { panel.texts().contains("全局测试") }
            panel.click("作用域")
            panel.await { panel.texts().contains("当前已绑定 0 个范围") }
            panel.click("作用于所有符合条件的请求；其他命中范围会按上方顺序叠加。")
            panel.await { panel.texts().contains("选择模板") }
            panel.click("全局测试")
            panel.await { panel.texts().contains("当前已绑定 1 个范围") }
            assertFalse(panel.texts().contains("暂无可选范围"))
        }
    }

    @Test
    fun orphanedBindingStaysVisibleAndRemovableAtLargeFont() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        val template = PromptTemplate(
            name = "角色规则",
            blocks = listOf(PromptBlock(title = "边界", content = "保持角色语气", order = 10)),
        )
        val orphan = PromptTarget.character("deleted-character")
        PromptInjectionStore.replace(
            PromptInjectionConfig(
                templates = listOf(template),
                bindings = listOf(PromptScopeBinding(templateId = template.id, target = orphan)),
            ),
        )
        val store = PromptInjectionUiStore(context)
        store.refresh()
        assertFalse(store.targets(PromptScopeType.CHARACTER).first { it.target == orphan }.enabled)

        Panel(dark = false, fontScale = 2f) {
            PromptScopeTargetsScreen(
                type = PromptScopeType.CHARACTER,
                store = store,
                onBack = {},
                onPick = {},
            )
        }.use { panel ->
            // 2x text legitimately pushes the first target below the fold; the binding row still
            // needs to be reachable by scrolling, rather than merely present in the lazy tree.
            panel.scrollUntil("已绑定：角色规则")
            panel.await {
                panel.texts().contains("已绑定：角色规则") && panel.texts().contains("解除绑定")
            }
            assertTrue(panel.texts().contains("目标已停用"))
            panel.capture("prompt-orphan-binding-320-font2")
            panel.click("解除绑定")
            panel.await {
                store.bindingFor(orphan) == null && !panel.texts().contains("已绑定：角色规则")
            }
        }
    }

    @Test
    fun editorUsesSortedBaselineForUnsavedChanges() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        val template = PromptTemplate(
            name = "顺序测试",
            blocks = listOf(
                PromptBlock(title = "第二", content = "后", order = 20),
                PromptBlock(title = "第一", content = "先", order = 10),
            ),
        )
        PromptInjectionStore.replace(PromptInjectionConfig(templates = listOf(template)))

        Panel(dark = false, fontScale = 1.3f) { PromptInjectionScreen {} }.use { panel ->
            panel.await { panel.texts().contains("顺序测试") }
            panel.click("顺序测试")
            panel.await { panel.texts().contains("编辑模板") }
            panel.back()
            panel.await { panel.texts().contains("固定提示词模板") }
            assertFalse(panel.texts().contains("放弃修改？"))
        }
    }
}
