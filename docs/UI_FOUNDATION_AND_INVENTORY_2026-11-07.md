# P01.4 UI 基础与全页清单

本项建立后续各批的共同规范和完整入口清单；P15 仍负责逐页视觉、交互与目标设备验收。不能把页面已列出等同于页面已精细审查。

## 复用当前设计

主题入口 `AgentAppTheme` 继续使用 Miuix、Classic/Omni 皮肤、Monet/深色/纯黑设置和用户界面缩放。现有 `EtaDesignTokens` 已包含色彩、圆角、玻璃/模糊、字重及内容/分组间距。本次补充从现有组件提取的尺寸，并实际接入任务等待卡、会话加载/失败、活动任务卡、权限健康卡和弹窗按钮行。

| Token | 当前默认值 | 用途 |
| --- | --- | --- |
| settingsHorizontalInset | 12dp | 设置/首页状态卡外边距 |
| chatHorizontalInset | 20dp | 聊天内状态说明 |
| cardContentPadding | 16dp | 卡内留白 |
| stateContentPadding | 24dp | 居中空态/加载区 |
| stateItemSpacing | 8dp | 标题、说明、操作间距 |
| minimumTouchTarget | 48dp | 可交互控件最小高度 |
| dialogActionSpacing | 20dp | 取消/确认按钮之间 |

字体继续从 `MiuixTheme.textStyles` 取 body1/body2/headline1/footnote1，标题字重取皮肤 token。说明文字不设固定高度，不以字符数裁剪重要错误；长名称只在列表预览单行省略，详情保留全文。用户字体缩放与界面缩放分别生效，不把字号写成 dp，不用固定字号假装适配。

`EtaStateMessage` 已被会话加载失败和无障碍等待共用，统一标题/说明/操作留白。`AgentConversationLoadState`/`AgentHistoryLoadStatus` 负责重试和加载，`MiuixDialogActions` 负责取消/确认顺序及危险动作颜色，`ChatMessageItem`/`AgentConversationMessages` 负责聊天消息显示。会议仍有独立内容容器，物理复用属于 P02.1；本项不会把它提前当作已完成。

## 状态与窗口规则

- 读取：先显示加载；加载失败留在原页面并给重试，不把未知数据当作空列表。
- 空态：说明当前为空以及有效的下一步；未授权与真空列表分开。
- 长任务：有可理解阶段、可取消/停止入口；等待有累计预算，历史恢复不再次显示活动任务。
- 错误：保持原输入/原文件；明确实际执行结果；已经成功的写操作不因后续观察失败被报告为全盘失败。
- 成功：只在实际保存/完成后反馈，浮层、通知、聊天和会议可对应到原运行。
- 控件：禁用原因可理解；按钮 48dp；操作行可换行，大字体时不互相遮挡；新增说明使用英/简/繁资源。
- 主壳 `AgentAppShell` 负责顶部/横向 safeDrawing，设置等自带骨架不重复叠加顶栏；底部/IME 由页面负责。聊天浮层与系统返回手势、文件抽屉/历史侧栏需要按 P02/P03 专项验证。
- 窄屏不强制双栏；宽屏可以并排；内容从窗口尺寸推导。键盘弹起后保留发送、停止和最后一条消息可见。

## 路由入口清单

清单来自 `AppRoute` 声明和实际 `entry<AppRoute...>` 注册；以下 47 个路由均登记。动态详情使用同一页面，但数据身份分别核对。

| 路由 | 实际页面/功能 | 源码入口 |
| --- | --- | --- |
| Home | AgentHomeScreen | [AgentHomeScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/home/AgentHomeScreen.kt) |
| Browser | AgentBrowserActivity 原生浏览器窗口；保存路由恢复到 Activity | [AgentBrowserActivity.kt](../app/src/main/kotlin/io/github/mangi/eta/agent/browser/AgentBrowserActivity.kt) |
| Terminal | TerminalEntryScreen | [ConsoleScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/ConsoleScreen.kt) |
| Tools | AgentToolsScreen | [AgentToolsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/tools/AgentToolsScreen.kt) |
| WebToolsSettings | WebToolsSettingsScreen | [WebToolsSettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/tools/WebToolsSettingsScreen.kt) |
| Skills | AgentSkillsScreen | [AgentSkillsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/skills/AgentSkillsScreen.kt) |
| Subagents | SubagentSettingsScreen | [SubagentSettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/subagent/SubagentSettingsScreen.kt) |
| TaskCenter | AgentTaskCenterScreen | [AgentTaskCenterScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/tasks/AgentTaskCenterScreen.kt) |
| A2AAgents | A2ASettingsScreen | [A2ASettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/a2a/A2ASettingsScreen.kt) |
| SelfLearning | LearningSettingsScreen | [LearningSettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/learning/LearningSettingsScreen.kt) |
| Characters | CharacterLibraryScreen | [CharacterLibraryScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/characters/CharacterLibraryScreen.kt) |
| CharacterDetail | CharacterDetailScreen | [CharacterDetailScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/characters/CharacterDetailScreen.kt) |
| CharacterEditor | CharacterEditorScreen | [CharacterEditorScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/characters/CharacterEditorScreen.kt) |
| CharacterPersona | CharacterPersonaScreen | [CharacterContextScreens.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/characters/CharacterContextScreens.kt) |
| CharacterMemory | CharacterMemoryScreen | [CharacterContextScreens.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/characters/CharacterContextScreens.kt) |
| Permissions | PermissionHealthScreen | [PermissionHealthScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/permissions/PermissionHealthScreen.kt) |
| SystemEnhance | SystemEnhanceScreen | [SystemEnhanceScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/enhance/SystemEnhanceScreen.kt) |
| Settings | SettingsScreen | [SettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/SettingsScreen.kt) |
| AppearanceSettings | AppearanceSettingsScreen | [AppearanceSettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/AppearanceSettingsScreen.kt) |
| ConversationArchiveSettings | ConversationArchiveSettingsScreen | [ConversationArchiveSettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/ConversationArchiveSettingsScreen.kt) |
| MeetingRooms | MeetingRoomsScreen | [MeetingRoomsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/meeting/MeetingRoomsScreen.kt) |
| MeetingRoom | MeetingRoomScreen | [MeetingRoomScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/meeting/MeetingRoomScreen.kt) |
| MeetingSettings | MeetingSettingsScreen | [MeetingSettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/MeetingSettingsScreen.kt) |
| SpeechSettings | SpeechSettingsScreen | [SpeechSettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/voice/SpeechSettingsScreen.kt) |
| SpeechRecognition | SpeechRecognitionScreen | [SpeechRecognitionScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/voice/SpeechRecognitionScreen.kt) |
| SpeechSynthesis | SpeechSynthesisScreen | [SpeechSynthesisScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/voice/SpeechSynthesisScreen.kt) |
| SpeechOss | SpeechOssScreen | [SpeechOssScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/voice/SpeechOssScreen.kt) |
| DataBackup | DataBackupScreen | [DataBackupScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/backup/DataBackupScreen.kt) |
| Memory | AgentMemoryScreen | [AgentMemoryScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/memory/AgentMemoryScreen.kt) |
| LinuxEnvironment | LinuxEnvironmentScreen | [LinuxEnvironmentScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/LinuxEnvironmentScreen.kt) |
| SharedFolders | SharedFoldersScreen | [SharedFoldersScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/SharedFoldersScreen.kt) |
| Workspace | WorkspaceScreen | [WorkspaceScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/WorkspaceScreen.kt) |
| Projects | ProjectsScreen | [ProjectsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/projects/ProjectsScreen.kt) |
| LinuxFiles | LinuxFilesScreen | [LinuxFilesScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/LinuxFilesScreen.kt) |
| ModelProviders | ModelProviderListScreen | [ModelProviderListScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/pages/providers/ModelProviderListScreen.kt) |
| CommunityCatalog | CommunityCatalogScreen | [CommunityCatalogScreens.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/pages/providers/CommunityCatalogScreens.kt) |
| CommunityCatalogProvider | CommunityCatalogProviderScreen | [CommunityCatalogScreens.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/pages/providers/CommunityCatalogScreens.kt) |
| McpServers | McpServersScreen | [McpServersScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/mcp/McpServersScreen.kt) |
| AgentMetrics | AgentMetricsScreen | [AgentMetricsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/metrics/AgentMetricsScreen.kt) |
| ConversationBranches | ConversationBranchesScreen | [ConversationBranchesScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/conversations/ConversationBranchesScreen.kt) |
| AgentMetricDetail | AgentMetricDetailScreen | [AgentMetricDetailScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/metrics/AgentMetricDetailScreen.kt) |
| AgentTokenPricing | AgentTokenPricingScreen | [AgentTokenPricingScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/metrics/AgentTokenPricingScreen.kt) |
| AgentTokenPriceEditor | AgentTokenPriceEditorScreen | [AgentTokenPricingScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/metrics/AgentTokenPricingScreen.kt) |
| McpServerDetail | McpServerDetailScreen | [McpServersScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/mcp/McpServersScreen.kt) |
| McpCatalog | McpCatalogScreen | [McpCatalogScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/mcp/McpCatalogScreen.kt) |
| ModelProviderDetail | ModelProviderDetailScreen | [ModelProviderDetailScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/pages/providers/ModelProviderDetailScreen.kt) |
| ModelProviderNew | ModelProviderDetailScreen | [ModelProviderDetailScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/pages/providers/ModelProviderDetailScreen.kt) |

## 页面与弹层源码清单

同时登记 50 个包含 Screen/Panel/Dialog 声明的 UI 源文件，覆盖未直接作为主路由的弹层。运行/语音浮层还包括 `AgentOverlayBubble`、`EtaVoicePanel`、附件选择器、消息菜单及 `ConversationSidePaneScaffold`；按所属批次并入检查。

| 页面/弹层文件 | 声明的界面 | 验证范围 |
| --- | --- | --- |
| [AgentAppRoot.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppRoot.kt) | ConversationContextBudgetDialog | 已盘点；视觉待 P15 |
| [AppearanceSettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/AppearanceSettingsScreen.kt) | AppearanceSettingsScreen | 已盘点；视觉待 P15 |
| [ConversationPanePanel.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/components/ConversationPanePanel.kt) | ConversationPanePanel | 已盘点；视觉待 P15 |
| [ItemDescriptionDialog.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/components/ItemDescriptionDialog.kt) | ItemDescriptionDialog | 已盘点；视觉待 P15 |
| [ConversationArchiveSettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/ConversationArchiveSettingsScreen.kt) | ConversationArchiveSettingsScreen | 已盘点；视觉待 P15 |
| [AdaptivePageLayout.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/layout/AdaptivePageLayout.kt) | isWideScreen, rememberIsWideScreen | 已盘点；视觉待 P15 |
| [MeetingSettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/MeetingSettingsScreen.kt) | MeetingSettingsScreen | 已盘点；视觉待 P15 |
| [CommunityCatalogScreens.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/pages/providers/CommunityCatalogScreens.kt) | CommunityCatalogProviderScreen, CommunityCatalogScreen | 已盘点；视觉待 P15 |
| [ModelProviderDetailScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/pages/providers/ModelProviderDetailScreen.kt) | ModelProviderDetailScreen | 已盘点；视觉待 P15 |
| [ModelProviderListScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/pages/providers/ModelProviderListScreen.kt) | ModelProviderListScreen | 已盘点；视觉待 P15 |
| [ProviderModelsTab.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/pages/providers/ProviderModelsTab.kt) | ModelEditDialog | 已盘点；视觉待 P15 |
| [A2ASettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/a2a/A2ASettingsScreen.kt) | A2ASettingsScreen | 已盘点；视觉待 P15 |
| [DataBackupScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/backup/DataBackupScreen.kt) | DataBackupScreen | 已盘点；视觉待 P15 |
| [AgentBrowserScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/browser/AgentBrowserScreen.kt) | AgentBrowserScreen | 已盘点；视觉待 P15 |
| [CharacterContextScreens.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/characters/CharacterContextScreens.kt) | CharacterMemoryScreen, CharacterPersonaScreen | 已盘点；视觉待 P15 |
| [CharacterDetailScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/characters/CharacterDetailScreen.kt) | CharacterDetailScreen | 已盘点；视觉待 P15 |
| [CharacterEditorScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/characters/CharacterEditorScreen.kt) | CharacterEditorScreen | 已盘点；视觉待 P15 |
| [CharacterLibraryScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/characters/CharacterLibraryScreen.kt) | CharacterLibraryScreen | 已盘点；视觉待 P15 |
| [ConversationBranchesScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/conversations/ConversationBranchesScreen.kt) | ConversationBranchesScreen | 已盘点；视觉待 P15 |
| [SystemEnhanceScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/enhance/SystemEnhanceScreen.kt) | SystemEnhanceScreen | 已盘点；视觉待 P15 |
| [AgentHomeScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/home/AgentHomeScreen.kt) | AgentHomeScreen | 已盘点；视觉待 P15 |
| [LearningSettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/learning/LearningSettingsScreen.kt) | LearningSettingsScreen | 已盘点；视觉待 P15 |
| [McpCatalogScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/mcp/McpCatalogScreen.kt) | McpCatalogScreen | 已盘点；视觉待 P15 |
| [McpServersScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/mcp/McpServersScreen.kt) | McpServerDetailScreen, McpServersScreen | 已盘点；视觉待 P15 |
| [MeetingRoomScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/meeting/MeetingRoomScreen.kt) | MeetingParticipantDialog, MeetingRoomDeleteDialog, MeetingRoomManagementDialog, MeetingRoomRenameDialog, MeetingRoomScreen | 已盘点；视觉待 P15 |
| [MeetingRoomsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/meeting/MeetingRoomsScreen.kt) | MeetingRoomCreateDialog, MeetingRoomsScreen | 已盘点；视觉待 P15 |
| [AgentMemoryScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/memory/AgentMemoryScreen.kt) | AgentMemoryScreen | 已盘点；视觉待 P15 |
| [AgentMetricDetailScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/metrics/AgentMetricDetailScreen.kt) | AgentMetricDetailScreen | 已盘点；视觉待 P15 |
| [AgentMetricsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/metrics/AgentMetricsScreen.kt) | AgentMetricsScreen | 已盘点；视觉待 P15 |
| [AgentTokenPricingScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/metrics/AgentTokenPricingScreen.kt) | AgentTokenPriceEditorScreen, AgentTokenPricingScreen | 已盘点；视觉待 P15 |
| [PermissionHealthScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/permissions/PermissionHealthScreen.kt) | PermissionHealthScreen | 已盘点；视觉待 P15 |
| [ProjectsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/projects/ProjectsScreen.kt) | ProjectsScreen | 已盘点；视觉待 P15 |
| [AgentSkillsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/skills/AgentSkillsScreen.kt) | AgentSkillsScreen | 已盘点；视觉待 P15 |
| [SubagentSettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/subagent/SubagentSettingsScreen.kt) | SubagentSettingsScreen | 已盘点；视觉待 P15 |
| [AgentTaskCenterScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/tasks/AgentTaskCenterScreen.kt) | AgentTaskCenterScreen | 已盘点；视觉待 P15 |
| [ConsoleScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/ConsoleScreen.kt) | ConsoleScreen, TerminalEntryScreen | 已盘点；视觉待 P15 |
| [DaemonTasksDialog.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/DaemonTasksDialog.kt) | DaemonTasksDialog | 已盘点；视觉待 P15 |
| [LinuxEnvironmentScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/LinuxEnvironmentScreen.kt) | LinuxEnvironmentScreen | 已盘点；视觉待 P15 |
| [LinuxFilesScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/LinuxFilesScreen.kt) | LinuxFilesScreen | 已盘点；视觉待 P15 |
| [SessionListDialog.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/SessionListDialog.kt) | SessionListDialog | 已盘点；视觉待 P15 |
| [SharedFoldersScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/SharedFoldersScreen.kt) | SharedFolderPickerDialog, SharedFoldersScreen | 已盘点；视觉待 P15 |
| [UserTerminalScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/UserTerminalScreen.kt) | InputPanel, UserTerminalScreen | 已盘点；视觉待 P15 |
| [WorkspaceScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/WorkspaceScreen.kt) | WorkspaceActionDialog, WorkspaceNameDialog, WorkspacePreviewDialog, WorkspaceScreen, WorkspaceSortDialog | 已盘点；视觉待 P15 |
| [AgentToolsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/tools/AgentToolsScreen.kt) | AgentToolsScreen | 已盘点；视觉待 P15 |
| [WebToolsSettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/screens/tools/WebToolsSettingsScreen.kt) | WebToolsSettingsScreen | 已盘点；视觉待 P15 |
| [SettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/SettingsScreen.kt) | SettingsScreen, SystemizerConfirmDialog | 已盘点；视觉待 P15 |
| [SpeechOssScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/voice/SpeechOssScreen.kt) | SpeechOssScreen | 已盘点；视觉待 P15 |
| [SpeechRecognitionScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/voice/SpeechRecognitionScreen.kt) | SpeechRecognitionScreen | 已盘点；视觉待 P15 |
| [SpeechSettingsScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/voice/SpeechSettingsScreen.kt) | SpeechSettingsScreen | 已盘点；视觉待 P15 |
| [SpeechSynthesisScreen.kt](../app/src/main/kotlin/io/github/mangi/eta/ui/voice/SpeechSynthesisScreen.kt) | SpeechSynthesisScreen | 已盘点；视觉待 P15 |

机器可读清单见 [清单 JSON](research/ui-surface-inventory-2026-11-07.json)。每个后续批次使用 [UI 检查模板](UI_BATCH_REVIEW_TEMPLATE.md)，记录 N/A、源码验证和真机结果，不能自动填“通过”。OPPO Find X7 Ultra/Android16、Android17/ColorOS17 OTA 的实际视觉与系统行为由用户后续验证。
