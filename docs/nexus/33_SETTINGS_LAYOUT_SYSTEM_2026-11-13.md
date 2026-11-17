# 设置布局规范与本轮覆盖（2026-11-13）

## 目标与范围

按用户要求统一设置内的布局设计语言：一级入口清晰、长页按任务切换、相同层级使用相同导航和分组方式。模型提供商和工具与设备是明确示例；覆盖同一设置树的相关详情、编辑和长弹窗。保留已有业务所有者和能力，不重新实现 D02–D14。

当前方案已由用户选择为“分组导航”，取代 0.7.4 首页四类切片。设置首页完整纵向展示所有分组；仅复杂详情固定顶部导航，短页直接展示。下面页面表为当前源码规则，文末旧版本交付保留为历史记录。

## 共用规则

1. 页面沿用既有 `MiuixScaffoldPage` / `MiuixScaffold`，返回、标题、安全区、宽屏正文和皮肤由共用骨架提供。
2. 多任务长页统一用 `NexusSettingsTabs`，放在滚动正文上方；分类栏不随正文滚走。单任务短页直接展示，不为统一而制造多余分类。
3. 分类标签使用主题色、统一圆角和间距，触控区至少 48dp，提供 Tab 选择语义。最多四项且完整标签可容纳时等分；窄屏、大字体或更多项目使用横向滚动，长标签允许换行，选中项滚动可见；导航最大宽度 920dp。宽度由当前字体实际测量，不按汉字个数估算。
4. 每个分类分别持有滚动位置。分类条件只切换内容，未保存草稿、数据订阅、控制器和在途操作归页面所有，不能因切分类销毁。根设置及工具开关写入使用页面级协程作用域。
5. 一级栏目使用标题和分组卡片；服务连接、能力、授权、诊断、危险操作按任务分开。共用 `NexusSettingsGroup` / `NexusSettingsSectionTitle` 复用既有间距和玻璃表面，既有专用卡片保留其状态表达。
6. 错误、活动状态、取消/停止和必要保存入口跨分类可达。备份的待重启、进度和取消保留在公共区；语音录制/试听状态和停止也不随分类消失。
7. 长弹窗正文限制高度并滚动，操作留在正文外。共用 `MiuixDialogActions` 在窄于 320dp 或字体倍率至少 1.3 时纵排，否则横排；按钮至少 48dp，危险操作保留危险色。弹窗内选择使用现有 `DialogChoicePreference`，不叠加多排分类栏。
8. 新分类和栏目文案提供英文、简中、繁中；不借本次布局任务迁移全部历史文案。CLASSIC、OMNI、AURORA、FROST_GLASS、LIQUID_GLASS 使用同一布局与业务状态，颜色、字体和表面由当前主题提供；新安装仍默认 AURORA，已有选择不覆盖。

## 页面与层级覆盖

下列路径均相对 `app/src/main/kotlin/io/github/mangi/eta/ui/`。

| 区域 | 结果 | 主要源码 |
|---|---|---|
| 设置首页 | 无分类栏，模型与推理、记忆与智能体、自动化与能力、工具与设备、系统助手与手势、语音与外观、项目与数据、权限与状态、关于纵向展示 | `SettingsScreen.kt` |
| 工具与设备 | 首页仅一个入口；详情同页呈现六个功能开关和四个服务权限入口，不再拆两个 Tab | `SettingsScreen.kt`、`navigation/AppRoute.kt`、`app/AgentAppRoot.kt` |
| 数字助理 | 默认助理 / 模型接管 / 语音唤起，独立滚动；框架断开时保留禁用选项和连接提示；沿用原持久化所有者 | `SettingsScreen.kt`、`navigation/AppRoute.kt` |
| 搜索手势 | 独立短页直接呈现手势条和双指两开关；保留框架权限边界 | `SettingsScreen.kt`、`navigation/AppRoute.kt` |
| 提供商列表 | 服务商 / 模型用途 / 候选路由 / 运行偏好，搜索和已有连接放服务商类 | `pages/providers/ModelProviderListScreen.kt` |
| 提供商详情 | 连接 / 模型 / 行为 / 请求头；新建时隐藏尚无所有者的模型类；模型 owner 保留 | `pages/providers/ModelProviderDetailScreen.kt`、`ProviderHeadersEditor.kt` |
| 模型编辑和候选弹窗 | 配置 / 能力；各自滚动；候选弹窗正文有界、操作固定 | `pages/providers/ProviderModelsTab.kt`、`ModelProviderListScreen.kt` |
| 外观 | 主题 / 字体与显示 / 效果与手势；五种皮肤、Monet、字体、缩放与原导航设置完整保留 | `AppearanceSettingsScreen.kt` |
| 备份与迁移 | 备份与恢复 / 自动备份 / 迁移；公共进度、取消、结果、重启入口；长恢复/导出弹窗滚动 | `screens/backup/BackupCenterScreen.kt` |
| 能力中心 | 概览 / 发现 / 连接 / 来源统一固定导航；添加来源类型统一选择样式 | `screens/capability/CapabilityCenterScreen.kt` |
| 连接中心 | 连接 / 本机能力 / 网络能力 / 测试与审计；协议改用选择行 | `screens/capability/ConnectorCenterScreen.kt` |
| API 连接 | 列表与详情保留；添加弹窗拆连接 / 授权，草稿/错误/导入入口保留；详情底部操作换行 | `screens/capability/ApiConnectorsScreen.kt` |
| MCP | 列表连接 / 批量迁移；详情连接 / 工具；错误和在途状态不隐藏 | `screens/mcp/McpServersScreen.kt` |
| 能力包 | 已安装 / 预置；详情统一五类导航；工具移入工具与权限，模型/UI 集成移入扩展 | `screens/capability/PackageCenterScreen.kt` |
| A2A | 连接 / 本机服务 / 授权 / 任务；待审批与异常计数公共可见 | `screens/a2a/A2ASettingsScreen.kt` |
| 权限 | 系统权限 / 审批策略 / 环境信息 | `screens/permissions/PermissionHealthScreen.kt` |
| 系统增强 | 授权与连接 / 功能说明，概况保留 | `screens/enhance/SystemEnhanceScreen.kt` |
| 工具目录 | 当前设备 / 全部，统一固定导航 | `screens/tools/AgentToolsScreen.kt` |
| 网页工具 | 服务 / 自定义搜索 / 自定义抓取；共享草稿和保存区，托管服务接口类有说明 | `screens/tools/WebToolsSettingsScreen.kt` |
| Linux | 配置 / 文件 / 可选工具；状态在每类保留，安装 owner 位于条件外 | `screens/terminal/LinuxEnvironmentScreen.kt` |
| Linux 弹窗 | 下载源分已配置 / 添加源，发行版/来源类型改选择行；资源限制正文可滚动，操作固定 | `screens/terminal/LinuxSourceSettingsRow.kt`、`LinuxResourceSettingsRow.kt` |
| 本地模型 | 概览 / 模型 / 运行统一固定导航和独立滚动 | `screens/localmodel/LocalModelsScreen.kt` |
| 记忆 | 记忆库 / 文档 / 学习 / 策略；记忆文档沿用专用编辑 | `screens/memory/MemoryReviewScreen.kt`、`AgentMemoryScreen.kt` |
| 学习及自动整理 | 学习固定分类；策略内提取 / 计划 / 用量；内嵌学习移除重复维护入口，最多两层分类栏 | `screens/learning/LearningSettingsScreen.kt`、`screens/memory/MemoryAutomationSettingsPanel.kt` |
| 子 Agent | 默认 / 内置 / 自定义；详情运行 / 角色 / 工具；编辑五类，身份、角色与工具分层 | `screens/subagent/SubagentSettingsScreen.kt` |
| 语音识别 | 服务、唤醒、声纹、结果按任务切换；控制器外置，活动状态/错误/结果公共可见 | `voice/SpeechRecognitionScreen.kt` |
| 语音合成 | 服务 / 音色 / 试听；草稿与音色加载 owner 保留，播放状态和停止公共可见 | `voice/SpeechSynthesisScreen.kt`、`SpeechVoiceFields.kt`、`SpeechLocalVoiceComponents.kt` |
| 角色 | 库角色 / 指令固定导航；详情对话 / 管理；编辑设定 / 对话 / 世界书 / 资料 | `screens/characters/CharacterLibraryScreen.kt`、`CharacterDetailScreen.kt`、`CharacterEditorScreen.kt` |
| 提示词 | 原四类改固定导航；模板信息 / 内容块；导入预览和操作状态上移保留 | `screens/prompt/PromptInjectionScreen.kt`、`PromptTemplateEditorScreen.kt`、`PromptTransferScreen.kt` |
| 会议 | 分发 / 上下文 / 对话，共用分组与导航 | `MeetingSettingsScreen.kt` |
| 自动任务 | 任务 / 记录；编辑基本信息 / 触发与条件 / 执行步骤 / 工具，保存和错误跨类保留；返回编辑保留列表位置 | `screens/tasks/AutomationCenterScreen.kt` |
| 用量统计 | 概览 / 记录 / 管理；选择日期直接进入对应记录 | `screens/metrics/AgentMetricsOverviewScreen.kt` |

## 保持直接布局的页面

已对相关分支作有限检查。服务商连接/账号控制和模型用途选择器属于专用连接或选择流程；语音总览、OSS、自动归档、人设、剧情记忆、价格编辑、任务单步骤编辑为单用途短表单；世界书条目已有内容/触发/插入分组；提示词目标/绑定/导出选择、目录浏览、单次运行详情已有独立任务边界。它们继续沿用共用骨架和操作样式，不额外添加分类。此项不代表对所有历史页面作了全量视觉审计。

## 验证与交付记录

当前 0.7.5 已实现上述分组导航及两套玻璃皮肤。新增玻璃的材质、降级、定向检查和最终安装包见文末最新条目及文档 31、22；以下 0.7.4 的检查与交付属于先前方案。

最终定向验证及桌面安装包记录见源码文档 31 和 22 的“设置统一布局”条目。仅运行当前路由、语言资源和提供商草稿相关验证，不重复历史业务整套测试。

证据边界：源码编译和上述定向检查不能代替三种皮肤在用户手机上的窄屏、大字体、软键盘、焦点、返回和长内容视觉验收；没有自动安装手机，也不声称已完成所有设备稳定性或性能验收。

定向验证结果：`AppRouteSerializationTest` 2 项、`LocaleResourcesTest` 3 项、`ProviderComponentsTest` 11 项，共 16 项通过；主 Kotlin 与测试源码编译通过。记录见 `build/settings-layout-minimal.log`。


### 设置统一布局正式桌面交付（2026-11-13，20/20 完成）

已完成 0.7.4 / 2026111302 的正式 Release 构建，桌面安装包：`C:\Users\29136\Desktop\Nexus-v0.7.4-settings-layout-20261113.apk`，62,240,104 bytes，SHA-256 `bc483cf9b22d504dc34c7be9f96c4c5478c1daddaf16afe49f68b2ac8ad228b6`。正式 v2/v3 签名通过，证书 `0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd` 与先前正式包一致；应用 ID `io.github.zhh_lgtm.nexus`、非 Debug/testOnly、ZIP 对齐、16 个 ARM64 原生库 16KiB 兼容校验通过。桌面附 `.sha256`、`.verification.json` 和 `.说明.txt`，构建日志 `build/settings-layout-release.log`，原始交付报告 `build/reports/nexus-delivery/release-0.7.4/verification.json`。

本批 19 个既有功能加本轮统一设置布局一个功能组，达到 20/20 并完成正式包交付；后续批次从 0/20 计。构建使用 `-SkipTests`，没有重复已通过的 16 项定向检查或全量业务测试。临时构建环境修改已恢复；没有安装手机，设备视觉与长期稳定性边界保持独立待验。

## 分组导航与两套玻璃皮肤正式交付（2026-11-13，0.7.5）

用户已选择完整网页方案中的“分组导航”，并要求后续不再派子 Agent。本次由主 Agent 独立落地；此前“等待用户选择”仅为历史记录。本次是已交付布局的返工与皮肤适配，不用页面数、选项数或预览控件数增加 20 项交付计数，也不重做 D02–D14。

| 范围 | 当前源码行为 |
|---|---|
| 设置首页 | 移除四类切片，所有原栏目纵向完整显示；模型、记忆与角色、自动化与能力、系统助手、语音、外观、语言、项目、工作区、备份、会话归档、权限与关于入口保留。生成文件归档仍由工作区所有者管理，与会话自动归档分开 |
| 工具与设备 | 独立入口；短页同屏顺序呈现六个开关及工具目录、网页工具、Linux、权限四个入口，移除额外二级 Tab；继续使用原设置控制面 |
| 数字助理与搜索手势 | 新增真实可序列化路由；默认助理、模型接管、语音唤起三个分区保留独立滚动；搜索手势短页直接显示两开关；框架未连接时保留禁用选项和连接提示，不绕过权限 |
| 复杂详情 | 模型提供商、连接、能力、备份、语音等复用既有顶部导航及业务状态；统一常见四项以内按实际文字宽度决定等分，窄屏/大字体横向滚动并显示选中项，48dp 最小触控区；短页不增加分类 |
| 外观设置 | 主题、字体与显示、效果与手势三个分区；保留原三皮肤、Monet 九种风格/颜色、纯黑、五字体、80–110% 缩放、两种顶部模糊和两种返回设置。新增柔雾玻璃（frost_glass）、清透液晶（liquid_glass）；新安装默认 AURORA，已有显式选择保留 |
| 共用材质 | 两套皮肤共享原调色与可读前景色。柔雾用较高面覆盖和柔化，清透降低面覆盖并在选中导航、主会话模式胶囊及新会话胶囊使用原创圆角镜片边缘折射；设置分组、顶栏、会话输入区、内嵌选择器和通用弹窗操作区共用细边/高光/阴影 |
| 稳定与性能 | 复用已有 Miuix 依赖；设置/主会话顶栏与固定导航共用单一静态背景取样源，避免采样包含镜片自身的父层。会话底部复用原消息 backdrop。普通卡片仅静态材质，不建每卡模糊层；无常驻时间动画。关闭模糊、低内存设备、无硬件加速或不支持 RuntimeShader 时回退静态材质；软件 Canvas 绘制直接绕开 GPU 背景，文字与图标不经过折射 |
| 共同所有者 | 外观仍通过 AppearanceSettingsRepository / SettingsDataStore / 控制面持久化，展示 profile 与 Agent 外观 schema 同步增加两个真实选项；未新增 UI 直写 DAO、Runtime 或外部发送 |

最小验证共 **21 个不同用例最终通过**：路由 2、语言资源 3、外观模型 3、外观仓库 4、既有设计参数 3、玻璃降级/原生 Skia shader 编译 2、展示 profile 3、窄屏大字体实际外观页切换 1。首次持久化用例以尚未初始化的 provider=null 作基线，触发既有内置提供商初始化；修正夹具为真实已配置模型后，只重跑失败用例，并补运行当时未执行的 profile 与界面检查，未重复其余已通过用例。界面检查覆盖 320dp、1.3 倍字体、两玻璃浅/深色及静态回退，输出六张外观分区截图并抽查浅/深两张；不能据此声称手机 GPU 折射效果已经实测。最后缩短皮肤说明，避免大字体长说明占用数行。

主 Kotlin、单元测试源码与正式 Release 编译成功。测试记录：`build/grouped-glass-minimal.log`、`build/grouped-glass-repair.log`、`build/reports/grouped-glass-delivery/tests.json`；首次 XML 保留于 `build/reports/grouped-glass-initial/`。打包收尾修正静态背景的采样位置与绘制顺序后，只重建正式产物，没有重跑业务测试。最终打包日志 `build/grouped-glass-delivery.log`。未执行全量回归、未自动安装手机、未回读旧聊天。

最终桌面包：`C:\Users\29136\Desktop\Nexus-v0.7.5-grouped-liquid-glass-20261113.apk`，**62,251,408 bytes**，版本 **0.7.5 / 2026111303**，SHA-256 **`2338ea20a04be5d10a28dfea9a39f33a81332e8134df0aca57959b2184ad6a7b`**。应用 ID `io.github.zhh_lgtm.nexus`，正式 v2/v3 签名、非 Debug/testOnly、ZIP 对齐及全部 16 个 ARM64 原生库 16KiB 兼容性通过。证书 SHA-256 `0dcae258b59db6ea5d07d53c6d394c6fb6668e6adcec8fa0add33f8fd03339dd`，与此前正式包一致。桌面附 `.sha256`、`.verification.json`、`.说明.txt`；原报告 `build/reports/nexus-delivery/release-0.7.5/verification.json`。

切换路径：设置 → 外观与主题 → 主题 → 界面皮肤 → 柔雾玻璃 / 清透液晶。两种玻璃按用户主动选择启用；设备上最终的背景折射观感、滚动帧率、系统大字体/键盘、最深弹层和长期功耗仍由用户装机体验确认，不把编译或静态截图描述为这些设备验收已经完成。


## 2026-11-15 用户批准的新一版视觉细则

本轮明确按新反馈执行：设置浅色底 `#F4F5F7`、纯白卡片、16dp 连续圆角和左右页边、无硬边框、极轻阴影；共享组内卡片间距 8dp，小标题上 24dp/下 8dp；Preference 保留 16dp 行内空间及右侧箭头留白。功能图标统一 24dp 单层深灰，无光晕底块；主文字 `#1C1C1E` 中等字重，辅助 13sp/`#8E8E93`，分隔线缩进到文字列。

设置通过专用局部主题实施；聊天玻璃输入框保留。聊天附件、技能与 MCP 二级、运行审批和思考弹层另用统一 ComposerPopup：同宽、同底色、同轮廓、同条目与选择状态。详见 [37 实施记录](37_UI_LAYERING_REFINEMENT_2026-11-15.md)。此规则取代此前与该授权范围冲突的卡片边框、渐变底板与图标光晕说明。

