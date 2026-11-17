package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.res.Configuration
import io.github.mangi.eta.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocaleResourcesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun supportedLocalesSelectExpectedResourcesAndOthersFallBackToEnglish() {
        assertEquals("记事本未能保存。要放弃未保存的修改并关闭吗？", localizedString("zh-CN", R.string.notepad_discard_unsaved_message))
        assertEquals("Discard and close", localizedString("fr-FR", R.string.notepad_discard_and_close))
        assertEquals("一般對話", localizedString("zh-TW", R.string.chat_mode_chat))
        assertEquals("Loading files…", localizedString("en-US", R.string.artifact_browser_loading))
        assertEquals("文件列表暂不可读取，请检查访问权限后重试。", localizedString("zh-CN", R.string.artifact_browser_load_failed))
        assertEquals("無法讀取工作副本。", localizedString("zh-TW", R.string.project_copies_load_failed))
        assertEquals("Expand options", localizedString("fr-FR", R.string.dialog_choice_expand))
        assertEquals("模型，目前 測試", localizedString("zh-TW", R.string.dialog_choice_current_value, "模型", "測試"))
        assertEquals("Loading projects…", localizedString("en-US", R.string.projects_loading))
        assertEquals("正在加载项目…", localizedString("zh-CN", R.string.projects_loading))
        assertEquals("正在載入專案…", localizedString("zh-TW", R.string.projects_loading))
        assertEquals("Could not load projects.", localizedString("fr-FR", R.string.projects_load_failed))
        assertEquals("无法加载项目。", localizedString("zh-CN", R.string.projects_load_failed))
        assertEquals("無法載入專案。", localizedString("zh-TW", R.string.projects_load_failed))
        assertEquals("Loading project details…", localizedString("en-US", R.string.projects_details_loading))
        assertEquals("正在加载项目详情…", localizedString("zh-CN", R.string.projects_details_loading))
        assertEquals("正在載入專案詳細資料…", localizedString("zh-TW", R.string.projects_details_loading))
        assertEquals("Could not load project details.", localizedString("fr-FR", R.string.projects_details_load_failed))
        assertEquals("无法加载项目详情。", localizedString("zh-CN", R.string.projects_details_load_failed))
        assertEquals("無法載入專案詳細資料。", localizedString("zh-TW", R.string.projects_details_load_failed))
        assertEquals("Loading local capability data…", localizedString("en-US", R.string.capability_local_data_loading))
        assertEquals("正在加载本机能力信息…", localizedString("zh-CN", R.string.capability_local_data_loading))
        assertEquals("正在載入本機能力資訊…", localizedString("zh-TW", R.string.capability_local_data_loading))
        assertEquals("Could not load local capability data.", localizedString("fr-FR", R.string.capability_local_data_failed))
        assertEquals("无法加载本机能力信息。", localizedString("zh-CN", R.string.capability_local_data_failed))
        assertEquals("無法載入本機能力資訊。", localizedString("zh-TW", R.string.capability_local_data_failed))
        assertEquals("Settings", localizedString("en-US", R.string.route_settings))
        assertEquals("Import A2A pairing info from clipboard", localizedString("en-US", R.string.a2a_import_pairing_from_clipboard))
        assertEquals("Create scheduled task", localizedString("en-US", R.string.task_new_scheduled))
        assertEquals("新建定时任务", localizedString("zh-CN", R.string.task_new_scheduled))
        assertEquals("新增排程任務", localizedString("zh-TW", R.string.task_new_scheduled))
        assertEquals("Run now", localizedString("fr-FR", R.string.task_run_now))
        assertEquals("启用任务", localizedString("zh-CN", R.string.task_enable))
        assertEquals("暫停任務", localizedString("zh-TW", R.string.task_pause))
        assertEquals("从剪贴板导入 A2A 配对信息", localizedString("zh-CN", R.string.a2a_import_pairing_from_clipboard))
        assertEquals("從剪貼簿匯入 A2A 配對資訊", localizedString("zh-TW", R.string.a2a_import_pairing_from_clipboard))
        assertEquals("Add A2A agent", localizedString("fr-FR", R.string.a2a_add_agent))
        assertEquals("Loading MCP servers…", localizedString("en-US", R.string.mcp_servers_loading))
        assertEquals("正在加载 MCP 服务器…", localizedString("zh-CN", R.string.mcp_servers_loading))
        assertEquals("正在載入 MCP 伺服器…", localizedString("zh-TW", R.string.mcp_servers_loading))
        assertEquals("Could not load MCP servers.", localizedString("fr-FR", R.string.mcp_servers_load_failed))
        assertEquals("无法加载 MCP 服务器。", localizedString("zh-CN", R.string.mcp_servers_load_failed))
        assertEquals("無法載入 MCP 伺服器。", localizedString("zh-TW", R.string.mcp_servers_load_failed))
        assertEquals("删除 Agent", localizedString("zh-CN", R.string.a2a_delete_agent))
        assertEquals("刪除 Agent", localizedString("zh-TW", R.string.a2a_delete_agent))
        assertEquals("设置", localizedString("zh-CN", R.string.route_settings))
        assertEquals("Settings", localizedString("en-US", R.string.action_settings))
        assertEquals("设置", localizedString("zh-CN", R.string.action_settings))
        assertEquals("Delete", localizedString("en-US", R.string.action_delete))
        assertEquals("删除", localizedString("zh-CN", R.string.action_delete))
        assertEquals("刪除", localizedString("zh-TW", R.string.action_delete))
        assertEquals("Refresh capability center", localizedString("en-US", R.string.capability_center_refresh))
        assertEquals("刷新能力中心", localizedString("zh-CN", R.string.capability_center_refresh))
        assertEquals("重新整理能力中心", localizedString("zh-TW", R.string.capability_center_refresh))
        assertEquals("设置", localizedString("zh-SG", R.string.route_settings))
        assertEquals("設定", localizedString("zh-TW", R.string.route_settings))
        assertEquals("設定", localizedString("zh-HK", R.string.route_settings))
        assertEquals("昨日", localizedString("zh-TW", R.string.time_yesterday))
        assertEquals("Settings", localizedString("fr-FR", R.string.route_settings))
        assertEquals("Appearance & Theme", localizedString("en-US", R.string.appearance_title))
        assertEquals("外观与主题", localizedString("zh-CN", R.string.appearance_title))
        assertEquals("外觀與主題", localizedString("zh-TW", R.string.appearance_title))
        assertEquals("Appearance & Theme", localizedString("fr-FR", R.string.appearance_title))
        assertEquals("1 model", localizedQuantity("en-US", R.plurals.provider_models_count, 1))
        assertEquals("2 models", localizedQuantity("en-US", R.plurals.provider_models_count, 2))
    }

    @Test
    fun settingsCategoriesAndToolsRouteFollowSupportedLocales() {
        assertEquals("Intelligence", localizedString("en-US", R.string.settings_tab_intelligence))
        assertEquals("智能", localizedString("zh-CN", R.string.settings_tab_intelligence))
        assertEquals("智慧", localizedString("zh-TW", R.string.settings_tab_intelligence))
        assertEquals("Tools & devices", localizedString("fr-FR", R.string.settings_tools_devices_title))
        assertEquals("工具与设备", localizedString("zh-CN", R.string.settings_tools_devices_title))
        assertEquals("工具與裝置", localizedString("zh-TW", R.string.settings_tools_devices_title))
        assertEquals("Headers", localizedString("en-US", R.string.settings_tab_headers))
        assertEquals("请求头", localizedString("zh-CN", R.string.settings_tab_headers))
        assertEquals("請求標頭", localizedString("zh-TW", R.string.settings_tab_headers))
        assertEquals("Digital assistant", localizedString("fr-FR", R.string.settings_digital_assistant_title))
        assertEquals("搜索手势", localizedString("zh-CN", R.string.settings_search_gestures_title))
        assertEquals("柔霧玻璃", localizedString("zh-TW", R.string.appearance_skin_frost_glass))
        assertEquals("清透液晶", localizedString("zh-CN", R.string.appearance_skin_liquid_glass))
    }

    @Test
    fun supplementalUiResourcesFollowLocaleWhileTechnicalTermsStayEnglish() {
        assertEquals("Choose Skills", localizedString("en-US", R.string.chat_select_skills))
        assertEquals("选择 Skills", localizedString("zh-CN", R.string.chat_select_skills))
        assertEquals("選擇 Skills", localizedString("zh-TW", R.string.chat_select_skills))
        assertEquals("Loading available Skills…", localizedString("en-US", R.string.chat_skill_choices_loading))
        assertEquals("正在加载可用的 Skill…", localizedString("zh-CN", R.string.chat_skill_choices_loading))
        assertEquals("正在載入可用的 Skill…", localizedString("zh-TW", R.string.chat_skill_choices_loading))
        assertEquals("Could not load available Skills.", localizedString("fr-FR", R.string.chat_skill_choices_load_failed))
        assertEquals("无法加载可用的 Skill。", localizedString("zh-CN", R.string.chat_skill_choices_load_failed))
        assertEquals("無法載入可用的 Skill。", localizedString("zh-TW", R.string.chat_skill_choices_load_failed))
        assertEquals("unavailable, tap to remove", localizedString("fr-FR", R.string.chat_unavailable_skill))
        assertEquals("The Skill selection is invalid or exceeds the limit. Adjust it and try again.", localizedString("en-US", R.string.chat_skill_selection_invalid))
        assertEquals("Skill 选择无效或超过本次运行上限，请调整后重试。", localizedString("zh-CN", R.string.chat_skill_selection_invalid))
        assertEquals("所選 Skills 有同優先級衝突，請只保留一個衝突 Skill 後重試。", localizedString("zh-TW", R.string.chat_skill_conflict))
        assertEquals("Join QQ group", localizedString("en-US", R.string.ui_about_join_qq_group))
        assertEquals("加入 QQ 群", localizedString("zh-CN", R.string.ui_about_join_qq_group))
        assertEquals("加入 QQ 群", localizedString("zh-TW", R.string.ui_about_join_qq_group))
        assertEquals("Open conversation notepad", localizedString("en-US", R.string.notepad_open))
        assertEquals("打开会话记事本", localizedString("zh-CN", R.string.notepad_open))
        assertEquals("開啟對話記事本", localizedString("zh-TW", R.string.notepad_open))
        assertEquals("群号：1107693849", localizedString("zh-CN", R.string.ui_about_qq_group_number, "1107693849"))
        assertEquals("群號：1107693849", localizedString("zh-TW", R.string.ui_about_qq_group_number, "1107693849"))
        assertEquals("Group number: 1107693849", localizedString("fr-FR", R.string.ui_about_qq_group_number, "1107693849"))
        assertEquals("QQ group number 1107693849 copied", localizedString("en-US", R.string.ui_about_qq_group_copied, "1107693849"))
        assertEquals("QQ 群号 1107693849 已复制", localizedString("zh-CN", R.string.ui_about_qq_group_copied, "1107693849"))
        assertEquals("QQ 群號 1107693849 已複製", localizedString("zh-TW", R.string.ui_about_qq_group_copied, "1107693849"))
        assertEquals("Import A2A pairing info from clipboard", localizedString("en-US", R.string.a2a_import_pairing_from_clipboard))
        assertEquals("从剪贴板导入 A2A 配对信息", localizedString("zh-CN", R.string.a2a_import_pairing_from_clipboard))
        assertEquals("從剪貼簿匯入 A2A 配對資訊", localizedString("zh-TW", R.string.a2a_import_pairing_from_clipboard))
        assertEquals("Import A2A pairing info from clipboard", localizedString("fr-FR", R.string.a2a_import_pairing_from_clipboard))
        assertEquals("Add A2A agent", localizedString("en-US", R.string.a2a_add_agent))
        assertEquals("添加 A2A Agent", localizedString("zh-CN", R.string.a2a_add_agent))
        assertEquals("新增 A2A Agent", localizedString("zh-TW", R.string.a2a_add_agent))
        assertEquals("Refresh Agent Card", localizedString("en-US", R.string.a2a_refresh_agent_card))
        assertEquals("刷新 Agent Card", localizedString("zh-CN", R.string.a2a_refresh_agent_card))
        assertEquals("重新整理 Agent Card", localizedString("zh-TW", R.string.a2a_refresh_agent_card))
        assertEquals("Delete agent", localizedString("en-US", R.string.a2a_delete_agent))
        assertEquals("删除 Agent", localizedString("zh-CN", R.string.a2a_delete_agent))
        assertEquals("刪除 Agent", localizedString("zh-TW", R.string.a2a_delete_agent))
        assertEquals("Current device", localizedString("en-US", R.string.capability_current_device))
        assertEquals("当前设备", localizedString("zh-CN", R.string.capability_current_device))
        assertEquals("目前裝置", localizedString("zh-TW", R.string.capability_current_device))
        assertEquals("Current device", localizedString("fr-FR", R.string.capability_current_device))
        assertEquals("Nexus is running", localizedString("en-US", R.string.execution_title))
        assertEquals("Nexus 正在运行", localizedString("zh-CN", R.string.execution_title))
        assertEquals("Nexus 正在執行", localizedString("zh-TW", R.string.execution_title))
        assertEquals("Environment configuration", localizedString("en-US", R.string.linux_environment_configuration))
        assertEquals("环境配置", localizedString("zh-CN", R.string.linux_environment_configuration))
        assertEquals("環境設定", localizedString("zh-TW", R.string.linux_environment_configuration))
        assertEquals("View description", localizedString("en-US", R.string.ui_view_description))
        assertEquals("查看说明", localizedString("zh-CN", R.string.ui_view_description))
        assertEquals("查看說明", localizedString("zh-TW", R.string.ui_view_description))
        assertEquals("Retrying model request", localizedString("en-US", R.string.system_notice_model_retry))
        assertEquals("模型请求重试", localizedString("zh-CN", R.string.system_notice_model_retry))
        assertEquals("模型請求重試", localizedString("zh-TW", R.string.system_notice_model_retry))
        assertEquals(
            "1 task is running. Return to Nexus to check progress.",
            localizedQuantity("en-US", R.plurals.execution_summary, 1),
        )
        assertEquals(
            "2 tasks are running. Return to Nexus to check progress.",
            localizedQuantity("en-US", R.plurals.execution_summary, 2),
        )
        assertEquals(
            "已导入 1 个文件，其余文件无法读取或超过 32 MiB",
            localizedQuantity("zh-CN", R.plurals.capability_workspace_partial_import, 1),
        )
        assertEquals(
            "已匯入 2 個檔案，其餘檔案無法讀取或超過 32 MiB",
            localizedQuantity("zh-TW", R.plurals.capability_workspace_partial_import, 2),
        )
        for (locale in listOf("en-US", "zh-CN", "zh-TW", "fr-FR")) {
            assertEquals("Skills", localizedString(locale, R.string.route_skills))
            assertEquals("API Key", localizedString(locale, R.string.speech_api_key))
        }
    }

    @Suppress("DEPRECATION")
    private fun localizedString(languageTag: String, resourceId: Int, vararg formatArgs: Any): String {
        val resources = context.resources
        val configuration = Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return resources.getString(resourceId, *formatArgs)
    }

    @Suppress("DEPRECATION")
    private fun localizedQuantity(languageTag: String, resourceId: Int, quantity: Int): String {
        val resources = context.resources
        val configuration = Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return resources.getQuantityString(resourceId, quantity, quantity)
    }
}
