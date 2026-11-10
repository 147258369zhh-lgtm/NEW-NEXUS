package io.github.mangi.eta.ui.screens.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.skill.SkillFolderArchive
import io.github.mangi.eta.agent.skill.SkillPackageExportService
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.DetailListRow
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.EtaStatusPill
import io.github.mangi.eta.ui.components.EtaStatusTone
import io.github.mangi.eta.ui.components.EtaUiMetrics
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.SkillVersionUi
import io.github.mangi.eta.ui.model.canDeleteUserSkill
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.FileProvider
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun AgentSkillsScreen(
    state: AgentSkillsUiState,
    onAction: (AgentSkillsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(page) { if (page.isEmpty()) onAction(AgentSkillsAction.Refresh) }
    if (page.isNotEmpty()) {
        androidx.activity.compose.BackHandler { page = "" }
        if (page == "evolution") SkillEvolutionScreen { page = "" } else BoundaryToolsScreen { page = "" }
        return
    }
    var deleteTarget by remember { mutableStateOf<SkillItemUi?>(null) }
    var versionTarget by remember { mutableStateOf<SkillItemUi?>(null) }
    var restoreTarget by remember { mutableStateOf<Pair<SkillItemUi, SkillVersionUi>?>(null) }
    var exportTarget by remember { mutableStateOf<SkillItemUi?>(null) }
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    var folderWorking by remember { mutableStateOf(false) }
    var exportWorking by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf<String?>(null) }
    var exportNotice by remember { mutableStateOf<SkillExportNotice?>(null) }
    val operationPending = state.isImporting || state.busySkillId != null || folderWorking || exportWorking
    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAction(AgentSkillsAction.ImportZip(uri.toString()))
    }
    val openZipPicker = {
        zipPicker.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
            ),
        )
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null && !folderWorking) {
            scope.launch {
                folderWorking = true
                folderError = null
                try {
                    val archiveUri = withContext(Dispatchers.IO) {
                        val directory = File(context.cacheDir, "workspace-share").also(File::mkdirs)
                        val archive = File(directory, "skill-folder-${UUID.randomUUID()}.zip")
                        SkillFolderArchive.create(context, uri, archive)
                        FileProvider.getUriForFile(context, "${context.packageName}.files", archive)
                    }
                    onAction(AgentSkillsAction.ImportZip(archiveUri.toString()))
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    folderError = failure.message
                        ?.lineSequence()
                        ?.firstOrNull()
                        ?.take(300)
                        ?: "无法读取所选文件夹"
                } finally {
                    folderWorking = false
                }
            }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            exportWorking = true
            exportNotice = null
            val temporary = withContext(Dispatchers.IO) {
                File(context.cacheDir, "workspace-share").also(File::mkdirs).let { directory ->
                    File(directory, "eta-skill-${UUID.randomUUID()}.zip")
                }
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    SkillPackageExportService(context.applicationContext).export(target.id, temporary)
                }
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "w")
                        ?: throw IllegalStateException(
                            resources.getString(R.string.skills_export_unable_open),
                        )
                    output.use { destination ->
                        temporary.inputStream().buffered().use { source ->
                            source.copyTo(destination)
                        }
                    }
                }
                exportNotice = SkillExportNotice(
                    title = resources.getString(R.string.skills_export_success_title),
                    message = resources.getString(
                        R.string.skills_export_success_summary,
                        result.displayName,
                        result.fileCount,
                        result.sizeBytes / 1024L,
                        result.sha256.take(16),
                    ),
                    isError = false,
                )
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                exportNotice = SkillExportNotice(
                    title = resources.getString(R.string.skills_export_failure_title),
                    message = failure.message
                        ?.lineSequence()
                        ?.firstOrNull()
                        ?.take(300)
                        ?: resources.getString(R.string.skills_export_unable_write),
                    isError = true,
                )
            } finally {
                temporary.delete()
                exportWorking = false
            }
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_skills),
        onBack = { onAction(AgentSkillsAction.NavigateBack) },
        modifier = modifier,
    ) {
        item(key = "skill-evolution") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                DetailListRow("经验技能", "自动提炼、证据验证与版本管理", Icons.Rounded.AutoAwesome, tone = EtaIconTone.AUTOMATION, onClick = { page = "evolution" })
                DetailListRow(
                    title = stringResource(R.string.skills_tools_permission_title),
                    subtitle = stringResource(R.string.skills_tools_permission_summary),
                    icon = Icons.Rounded.AdminPanelSettings,
                    tone = EtaIconTone.TOOL,
                    onClick = { page = "tools" },
                )
            }
        }
        val installed = state.skills.filter { it.installed }
        val needsReview = installed.filter { it.reviewRecommended || it.setupRequired }
        val builtinInstalled = installed.filter { it.source == "builtin" && it !in needsReview }
        val userInstalled = installed.filter { it.canDeleteUserSkill && it !in needsReview }
        val removed = state.skills.filter { !it.installed }

        if (installed.isNotEmpty()) {
            item(key = "skill-health") {
                SmallTitle("能力健康", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                SkillHealthCard(installedCount = installed.size, reviewCount = needsReview.size)
            }
        }

        item(key = "zip-import") {
            SmallTitle(stringResource(R.string.ui_install_087db6), modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = EtaUiMetrics.groupGap),
            ) {
                ArrowPreference(
                    title = if (state.isImporting) stringResource(R.string.skills_checking_package) else stringResource(R.string.skills_import_zip),
                    summary = if (state.isImporting) {
                        stringResource(R.string.skills_installing_package)
                    } else {
                        stringResource(R.string.skills_choose_package)
                    },
                    endActions = {
                        if (state.isImporting) {
                            InfiniteProgressIndicator(size = 20.dp)
                        }
                    },
                    enabled = !operationPending,
                    onClick = openZipPicker,
                )
                ArrowPreference(
                    title = if (folderWorking) "正在整理本地文件夹" else "从本地文件夹导入",
                    summary = if (folderWorking) {
                        "正在安全打包 SKILL.md 与资源文件"
                    } else {
                        "可选择单个 Skill 或包含多个 Skill 的父文件夹"
                    },
                    startAction = {
                        top.yukonga.miuix.kmp.basic.Icon(
                            Icons.Rounded.FolderOpen,
                            contentDescription = null,
                        )
                    },
                    endActions = {
                        if (folderWorking) InfiniteProgressIndicator(size = 20.dp)
                    },
                    enabled = !operationPending,
                    onClick = { folderPicker.launch(null) },
                )
            }
        }

        if (needsReview.isNotEmpty()) {
            item(key = "review") {
                SmallTitle("建议复核", modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = EtaUiMetrics.groupGap),
                ) {
                    needsReview.forEach { skill ->
                        SkillSwitchRow(
                            skill = skill,
                            enabled = !operationPending,
                            onToggle = { enabled ->
                                onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                            },
                            onExport = {
                                exportTarget = skill
                                exportPicker.launch(skill.id + ".zip")
                            },
                            onDelete = if (skill.canDeleteUserSkill) {
                                { deleteTarget = skill }
                            } else {
                                null
                            },
                            onOpenVersions = if (skill.versions.isNotEmpty()) {
                                { versionTarget = skill }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        if (builtinInstalled.isNotEmpty()) {
            item(key = "builtin") {
                SmallTitle(stringResource(R.string.ui_built_in_skills_1ceedf), modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = EtaUiMetrics.groupGap),
                ) {
                    builtinInstalled.forEach { skill ->
                        SkillSwitchRow(
                            skill = skill,
                            enabled = !operationPending,
                            onToggle = { enabled ->
                                onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                            },
                            onExport = {
                                exportTarget = skill
                                exportPicker.launch(skill.id + ".zip")
                            },
                            onOpenVersions = if (skill.versions.isNotEmpty()) {
                                { versionTarget = skill }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        if (userInstalled.isNotEmpty()) {
            item(key = "user") {
                SmallTitle(stringResource(R.string.ui_user_skills_748e7f), modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = EtaUiMetrics.groupGap),
                ) {
                    userInstalled.forEach { skill ->
                        SkillSwitchRow(
                            skill = skill,
                            enabled = !operationPending,
                            onToggle = { enabled ->
                                onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                            },
                            onExport = {
                                exportTarget = skill
                                exportPicker.launch(skill.id + ".zip")
                            },
                            onDelete = { deleteTarget = skill },
                            onOpenVersions = if (skill.versions.isNotEmpty()) {
                                { versionTarget = skill }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }

        if (removed.isNotEmpty()) {
            item(key = "removed") {
                SmallTitle(stringResource(R.string.ui_removed_4e5c49), modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = EtaUiMetrics.groupGap),
                ) {
                    removed.forEach { skill ->
                        BasicComponent(
                            title = skill.name,
                            summary = stringResource(R.string.ui_click_to_reinstall_dc60de),
                            enabled = !operationPending,
                            onClick = {
                                onAction(AgentSkillsAction.ReinstallBuiltin(skill.id))
                            },
                        )
                    }
                }
            }
        }

        if (state.skills.isEmpty() && state.isLoading) {
            item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(size = 24.dp)
                }
            }
        }
        if (state.skills.isEmpty() && !state.isLoading) {
            item(key = "empty") {
                ListEmptyState(
                    title = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                    summary = stringResource(R.string.skills_choose_package),
                    icon = Icons.Rounded.FolderZip,
                    action = {
                        TextButton(
                            text = stringResource(R.string.skills_import_zip),
                            enabled = !operationPending,
                            onClick = openZipPicker,
                        )
                    },
                )
            }
        }
    }

    state.replacement?.let { replacement ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.ui_replace_user_skills_250e98),
            summary = stringResource(R.string.skills_replace_summary, replacement.name, replacement.id),
            onDismissRequest = { onAction(AgentSkillsAction.CancelZipReplacement) },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.skills_replace),
                confirmEnabled = !operationPending,
                onCancel = { onAction(AgentSkillsAction.CancelZipReplacement) },
                onConfirm = { onAction(AgentSkillsAction.ConfirmZipReplacement) },
            )
        }
    }

    deleteTarget?.let { skill ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.ui_delete_user_skills_f319a9),
            summary = stringResource(R.string.skills_delete_summary, skill.name),
            onDismissRequest = { deleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.ui_delete_3755f5),
                destructive = true,
                confirmEnabled = !operationPending,
                onCancel = { deleteTarget = null },
                onConfirm = {
                    deleteTarget = null
                    onAction(AgentSkillsAction.DeleteSkill(skill.id))
                },
            )
        }
    }

    versionTarget?.let { skill ->
        WindowDialog(
            show = true,
            title = "版本记录",
            summary = "${skill.name} · 最多保留 3 个更新前版本",
            onDismissRequest = { versionTarget = null },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                skill.versions.forEach { version ->
                    val time = SimpleDateFormat("yyyy年M月d日 HH:mm", locale)
                        .format(Date(version.createdAt))
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        BasicComponent(
                            title = version.version?.let { "v$it" } ?: "历史版本",
                            summary = buildString {
                                version.sourceName?.let { append(it).append(" · ") }
                                append(time)
                                append(" · ").append(version.sizeBytes / 1024L).append(" KB")
                            },
                            enabled = !operationPending,
                            onClick = {
                                restoreTarget = skill to version
                                versionTarget = null
                            },
                        )
                    }
                }
                if (skill.versions.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EtaIconTile(Icons.Rounded.VerifiedUser, tone = EtaIconTone.TOOL)
                        Text(
                            text = "暂无历史版本",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            TextButton(
                text = "关闭",
                onClick = { versionTarget = null },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }

    restoreTarget?.let { (skill, version) ->
        WindowDialog(
            show = true,
            title = "恢复历史版本",
            summary = "将 ${skill.name} 恢复到" +
                (version.version?.let { " v$it" } ?: "上一个版本") +
                "。当前版本会先保存为新的历史版本。",
            onDismissRequest = { restoreTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = "恢复",
                confirmEnabled = !operationPending,
                onCancel = { restoreTarget = null },
                onConfirm = {
                    restoreTarget = null
                    onAction(AgentSkillsAction.RestoreSkillVersion(skill.id, version.id))
                },
            )
        }
    }

    exportNotice?.let { result ->
        WindowDialog(
            show = true,
            title = result.title,
            summary = result.message,
            onDismissRequest = { exportNotice = null },
        ) {
            TextButton(
                text = stringResource(R.string.ui_knew_cb63c6),
                onClick = { exportNotice = null },
                modifier = Modifier.fillMaxWidth(),
                colors = if (result.isError) {
                    ButtonDefaults.textButtonColors()
                } else {
                    ButtonDefaults.textButtonColorsPrimary()
                },
            )
        }
    }

    folderError?.let { message ->
        WindowDialog(
            show = true,
            title = "无法导入文件夹",
            summary = message,
            onDismissRequest = { folderError = null },
        ) {
            TextButton(
                text = "知道了",
                onClick = { folderError = null },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(),
            )
        }
    }

    state.notice?.let { notice ->
        WindowDialog(
            show = true,
            title = notice.title,
            summary = notice.message,
            onDismissRequest = { onAction(AgentSkillsAction.DismissNotice) },
        ) {
            TextButton(
                text = stringResource(R.string.ui_knew_cb63c6),
                onClick = { onAction(AgentSkillsAction.DismissNotice) },
                modifier = Modifier.fillMaxWidth(),
                colors = if (notice.isError) {
                    ButtonDefaults.textButtonColors()
                } else {
                    ButtonDefaults.textButtonColorsPrimary()
                },
            )
        }
    }
}

@Composable
private fun SkillHealthCard(installedCount: Int, reviewCount: Int) {
    val healthy = reviewCount == 0
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = EtaUiMetrics.groupGap)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            EtaIconTile(
                icon = Icons.Rounded.VerifiedUser,
                tone = if (healthy) EtaIconTone.SYSTEM else EtaIconTone.MEMORY,
                size = 40.dp,
                glyphSize = 21.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (healthy) "能力状态正常" else "$reviewCount 个 Skill 建议复核",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "已检查 $installedCount 个 Skill 的使用、依赖和版本状态",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            EtaStatusPill(
                text = if (healthy) "健康" else "需检查",
                tone = if (healthy) EtaStatusTone.Positive else EtaStatusTone.Warning,
            )
        }
    }
}

private data class SkillExportNotice(
    val title: String,
    val message: String,
    val isError: Boolean,
)
