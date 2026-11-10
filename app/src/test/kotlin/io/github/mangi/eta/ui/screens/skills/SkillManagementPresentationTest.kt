package io.github.mangi.eta.ui.screens.skills

import android.app.Application
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.SkillVersionUi
import io.github.mangi.eta.ui.screens.memory.MemoryLearningPresentationTest.Panel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "zh-rCN-w411dp-h896dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkillManagementPresentationTest {
    @Test
    fun skillHealthVersionsAndActionsUseExistingMiuixHierarchy() = runBlocking {
        Panel {
            AgentSkillsScreen(
                state = AgentSkillsUiState(
                    skills = listOf(
                        skill(
                            id = "project-report",
                            source = "user",
                            review = false,
                            versions = listOf(
                                SkillVersionUi(
                                    id = "version-demo",
                                    version = "1.2.0",
                                    sourceName = "团队 Skill 目录",
                                    createdAt = 1_760_000_000_000L,
                                    sizeBytes = 48_000L,
                                ),
                            ),
                        ),
                        skill(
                            id = "legacy-spreadsheet",
                            source = "user",
                            review = true,
                            qualityLabel = "长期未使用",
                            dependencyLabel = "未满足：Linux 环境",
                        ),
                    ),
                ),
                onAction = {},
            )
        }.use { panel ->
            panel.await { "能力健康" in panel.texts() }
            assertTrue(panel.texts().contains("2 个 Skill 建议复核") ||
                panel.texts().contains("1 个 Skill 建议复核"))
            panel.scrollUntil("项目报告")
            panel.capture("skill-health-overview")
            panel.scrollUntil("长期未使用 · 未满足：Linux 环境")
            panel.capture("skill-review-actions")
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h720dp-xhdpi")
    fun skillMetadataRemainsReadableOnNarrowDarkLargeTextScreen() = runBlocking {
        Panel(dark = true, fontScale = 1.3f) {
            AgentSkillsScreen(
                state = AgentSkillsUiState(
                    skills = listOf(
                        skill(
                            id = "long-user-skill-id",
                            displayName = "项目资料整理与企业周报自动生成",
                            source = "user",
                            review = true,
                            qualityLabel = "失败率偏高",
                            dependencyLabel = "待确认：Python 数据分析组件",
                            description = "读取授权的项目资料，核对数据来源并生成可追溯的周报，缺少依赖时保持暂停。",
                        ),
                    ),
                ),
                onAction = {},
            )
        }.use { panel ->
            panel.await { "能力健康" in panel.texts() }
            panel.scrollUntil("项目资料整理与企业周报自动生成")
            panel.capture("skills-narrow-dark-large-font")
            assertTrue(panel.texts().any { it.contains("失败率偏高") })
            assertTrue(panel.texts().any { it.contains("Python") })
            panel.scrollUntil("失败率偏高 · 待确认：Python 数据分析组件")
            panel.capture("skills-review-narrow-dark-large-font")
        }
    }

    private fun skill(
        id: String,
        displayName: String = "项目报告",
        source: String,
        review: Boolean,
        qualityLabel: String? = null,
        dependencyLabel: String? = null,
        description: String = "整理项目资料并生成可追溯报告。",
        versions: List<SkillVersionUi> = emptyList(),
    ) = SkillItemUi(
        id = id,
        name = displayName,
        description = description,
        source = source,
        enabled = true,
        installed = true,
        capabilities = listOf("scripts", "references"),
        sourceName = "用户安装",
        version = "1.2.0",
        installedAt = 1_759_000_000_000L,
        useCount = 12,
        lastUsedAt = 1_759_900_000_000L,
        reviewRecommended = review,
        qualityLabel = qualityLabel,
        qualityDetail = qualityLabel?.let { "$it；建议重新核对适用场景与依赖。" },
        dependencies = listOf("Python", "Linux"),
        prerequisiteLabel = dependencyLabel,
        prerequisiteDetail = dependencyLabel?.let { "$it。安装不会运行包内脚本。" },
        setupRequired = dependencyLabel != null,
        versions = versions,
    )
}
