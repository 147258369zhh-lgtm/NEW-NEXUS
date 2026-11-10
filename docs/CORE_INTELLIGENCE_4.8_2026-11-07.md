# Eta 4.8.0 核心智能升级与 PRO 源码研究

版本：`4.8.0` / `2026110710`
研究基线：Oo0520/Eta-por `5ac565675cb958a073bf956d0cf0a469998b99f9`
本地参考：`F:\\eta\\.reference\\eta-pro-20261107`

本次按实际执行代码研究参考项目，只吸收与 Eta 架构相容的设计，不整仓替换，也不把界面入口或配置项当作能力完成。双方根许可证均为 PolyForm Noncommercial 1.0.0，包含 `Required Notice: Copyright © 2026 蛮吉 (Mangi-11).`。后续如需复用具体文件，必须继续保留许可证和来源说明，并逐项核对技术依赖。

## 第一部分：小 PRO 研究结果

### 1. 自动形成 Skill

参考实现位置：

- `app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentExperienceReview.kt`
- `app/src/main/kotlin/io/github/mangi/eta/agent/skill/SkillAuthoringService.kt`
- 自学习内置 `SKILL.md` 与相关运行测试

实际机制：

- 成功聊天后启动独立复盘，新的前台运行会取消旧复盘。复盘限制为 180 秒、4 轮、12 次工具调用、一次 Skill 写入。
- `ExperienceReviewPolicy` 在工具操作达到 8 次、失败后恢复成功或用户明确提到 Skill/技能时，才认为存在可复盘信号。
- 复盘 Agent 只开放记忆和 Skill 工具，不开放设备操作。
- `SkillAuthoringService` 复用安装器事务写入 `SKILL.md`，检查 ID、目录、符号链接、特殊文件、嵌套 Skill 和文件大小，并用 SHA-256 做乐观版本校验。
- 它的优点是把 Skill 创建接入真实安装事务，而不是只保存一段聊天文本。
- 它的缺口是没有跨多个独立成功任务的确定性门槛；一次成功、模型主观判断或失败经验都可能进入创建链路。

对 Eta 的结论：不能照搬“自动创建即发布”。Eta 需要执行证据、去重、质量门槛、用户审阅、作用域和版本回滚。

### 2. 自动任务

参考实现位置：

- `app/src/main/kotlin/io/github/mangi/eta/agent/automation/AgentTaskRules.kt`
- `app/src/main/kotlin/io/github/mangi/eta/agent/automation/AgentTaskScheduler.kt`
- `AgentTriggerMonitorService` 及自动化测试

实际机制：

- 规则使用固定 JSON 校验，本地匹配时间、前台应用、屏幕、电量、网络、蓝牙、耳机、开机、记忆更新和任务结果等事件。
- 时间主要支持一次、间隔和每天；条件支持充电、网络、解锁及冷却/次数限制。
- 调度使用 AlarmManager、持久 JobScheduler 和 Room `fireKey` 去重。
- 事件监听通过仅在存在事件任务时启用的前台服务运行。
- 仓库文档明确没有文件夹监听、复杂条件图和多步骤流程编辑器。
- 因此它可以做可靠的事件任务基础，但达不到 Eta 所需的个人自动化工作流中心。

对 Eta 的结论：保留 Eta 已有 WorkManager、任务租约、幂等键、等待授权、会议/A2A 路由和失败不盲重放机制，在其上扩展触发器与有界流程解释器，避免并存两套调度器。

### 3. 临屏 / 灵屏 / 虚拟屏

参考实现位置：

- `app/src/main/kotlin/io/github/mangi/eta/agent/display/VirtualScreenSession.kt`
- `VirtualScreenUiTools.kt`
- `VirtualScreenUiTreeAvailability.kt`
- `VirtualScreenRunLease.kt`
- `VirtualScreenViewport.kt`
- `RootDisplayCommandMain.kt`
- `VirtualScreenRoutingPolicy.kt`

实际机制：

- Root 下用独立 `app_process` 创建 display，ImageReader 出帧，并保留独立显示会话。
- 工具链路可联合读取节点树、截图、文本和归一化坐标；动作后重新观察，而不是只返回一次截图。
- `VirtualScreenUiTreeAvailability` 用窗口、会话和手动输入代次判断节点树是否暂时不可用。
- `VirtualScreenRunLease` 防止迟到的取消关闭下一轮运行。
- `VirtualScreenViewport` 统一 FIT_CENTER 坐标映射，信箱留白不参与触摸。
- 该固定提交中 `launch_app` 已走真机主屏，`lingscreen_open_app` 才明确创建/进入灵屏；README 的自动路由描述与代码不一致。
- Root、受信任 display、焦点、输入法、应用多屏支持和 ROM 行为仍是硬约束。参考项目的测试不能证明 ColorOS 17 普遍兼容。

对 Eta 的结论：可借鉴会话、观察代次、运行所有权和坐标映射，但不适合整包替换 Eta 的 GUI 合同。Eta 采用分模块适配，明确虚拟屏失败，禁止静默退回主屏。

### 4. 提示词与工具边界

参考实现位置：

- `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentPromptBuilder.kt`
- `app/src/main/kotlin/io/github/mangi/eta/agent/prompt/PromptCustomization.kt`
- `app/src/main/kotlin/io/github/mangi/eta/agent/prompt/AgentToolPolicy.kt`
- `app/src/main/kotlin/io/github/mangi/eta/agent/security/PromptIntegrityGuard.kt`
- `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentModelClient.kt`

实际机制：

- 系统基础提示、设备合同、记忆、Skill、角色和附加提示分层组合。
- `PromptCustomization` 支持 append/replace、指纹和最近 5 个版本。
- 工具策略同时过滤模型可见目录和执行入口，`PromptIntegrityGuard` 做本地完整性检查。
- 不同 Profile、Skill 和子 Agent 可形成不同工具范围。
- 重要安全缺口是工具策略解析失败时回到 ALLOW_ALL。Eta 不能沿用这种失败放开策略。
- 提示词不是权限防火墙；获得终端/Root 的 Agent 仍可能执行等价系统命令。

对 Eta 的结论：借鉴分层和版本历史，但把持久全局边界、运行上限、子 Agent、Skill 和实际执行同时取交集；损坏配置失败关闭。

## 第二部分：Eta 系统升级结果

### 1. 自动 Skill 闭环

核心文件：

- `agent/skill/SkillEvolution.kt`
- `agent/skill/SkillEvolutionData.kt`
- `agent/skill/SkillPublicationJournal.kt`
- `agent/skill/SkillAuthoringService.kt`
- `agent/runtime/AgentRuntimeRunExecutor.kt`
- `ui/screens/skills/SkillEvolutionScreen.kt`

真实工作方式：

1. 只从实际执行过的运行收集有界工具事件；失败运行不生成成功经验。
2. 同一 Skill 至少需要 3 个独立任务的成功证据，才进入“待验证”。
3. 模型整理时无工具权限，只能返回结构化候选；它提到的工具必须来自这些真实成功工具集合。
4. 候选会检查代码、密钥等敏感信息；未知工具、越界工具和非法结构直接拒绝。
5. 语义相近的候选会合并，来源证据随之迁移；不同项目作用域的候选不能互相合并。
6. 候选、正文、工具、版本和来源都持久化；用户可编辑正文、载入历史版本、暂停、拒绝、重新验证。
7. “验证并启用”才把正文交给安全安装器发布。不会把自动提炼直接等同于正式能力。
8. 发布中断有恢复日志，能区分文件已安装但数据库未提交、并发版本冲突等情况。
9. 自动生成 Skill 按全局/项目/会议/会话作用域过滤，不向无关项目注入。
10. 同轮读取多个 Skill 时，能力按 Skill 工具并集合并，再与父运行和全局边界取交集。

这是有意的安全门槛：模型可以提炼，只有用户确认后才成为可自动调用的正式 Skill。

### 2. 自动任务与流程编排

核心文件：

- `fork/tasks/AutomationSpec.kt`
- `fork/tasks/AutomationTriggers.kt`
- `fork/tasks/AutomationInputFiles.kt`
- `fork/tasks/WorkflowEngine.kt`
- `fork/tasks/AgentTaskRuntimeRunner.kt`
- `fork/tasks/AutomationRepository.kt`
- `fork/tasks/AutomationMonitorService.kt`
- `ui/screens/tasks/AutomationCenterScreen.kt`

已接入：

- 时间：一次、间隔、每天、每周多选和每月。
- 事件：手动、充电、联网、解锁、Eta 启动、设备启动、目录新文件、文件更新、任务成功、任务失败、记忆更新、设备条件。
- 条件：充电、网络、锁屏三态，最低电量，以及 AND/OR 组合。
- 流程步骤：Agent、明确调用 Skill、等待、条件分支和通知。
- 执行控制：重复、步级超时、可安全重试、失败停止/继续、流程总超时。
- 管理与恢复：暂停、恢复、复制、删除、手动运行、每日事件上限、冷却、防重复、任务链循环检查、历史运行和实时日志。
- 文件触发使用 SAF `DocumentsContract` 扫描；首次建立基线，之后逐个处理新文件。
- 触发文件会复制到工具可读私有目录，限制 32 MiB 单文件、256 MiB 总容量，7 天清理。
- 文件 URI 和来源内容会作为带边界的触发数据注入，不会因此获得新工具权限。

本轮同时修复了 Android 15+ 的 SAF 查询兼容问题：目录扫描、触发文件导入和自动备份清理统一使用带 `Bundle` 的现代 `ContentResolver.query`，不再走旧五参数接口。

### 3. 临屏与虚拟屏

本轮采用参考项目中可独立验证的设计，而不是整包移植：

- `agent/virtualdisplay/VirtualDisplayViewport.kt` 提供 FIT_CENTER 映射并拒绝非法坐标。
- 信箱留白不响应 Agent 触摸。
- 拒绝 NaN、Infinity、多指和无效尺寸。
- 显式虚拟屏不可用时不再静默切到物理主屏。
- 虚拟屏开关撤销后，后续动作拒绝执行。
- 用户手动操作与 Agent 动作串行化；手动操作开始后旧观察代次失效。
- 恢复后旧节点索引不能继续使用，必须重新观察。
- 修复虚拟屏任务迁移条件，先匹配目标 display，再校验包名，避免关闭错误任务。

当前没有整包移植参考项目的 `VirtualScreenSession`、运行租约和完整空树恢复状态机。Eta 已吸收其中关键的坐标、所有权和代次思想，但剩余部分仍需按实际 ROM 逐项适配。

### 4. 提示词与工具边界

核心文件：

- `agent/runtime/AgentBoundaryStore.kt`
- `agent/runtime/AgentToolPolicy.kt`
- `agent/runtime/AgentRuntimeRunExecutor.kt`
- `agent/model/AgentModelClient.kt`
- `agent/subagent/SubagentExecutor.kt`
- `ui/screens/skills/AgentBoundaryScreen.kt`

已接入：

- 可编辑全局行为提示，最近 5 个版本可恢复。
- 工具按屏幕、终端/Root、浏览器、文件写入、设备设置、敏感读取、记忆、Skill 安装、MCP、子 Agent、自动任务等能力组管理。
- 可进一步逐工具开关。
- 损坏或无法解析的持久配置按失败关闭处理。
- 全局边界、父运行、子 Agent、Skill 与会议室工具在发布目录和执行两端共同校验。
- Skill 只能收窄工具范围，不能扩权。
- 子 Agent 使用执行时的动态边界，不沿用创建时的旧权限。
- 后台自动任务禁止递归创建、修改或触发其他自动任务。

这仍然不是系统沙箱。若用户授予 Root/终端，则模型可以通过终端执行等价操作，因此终端、Root、文件写入和设备设置必须作为独立高风险能力管理。

## 第三部分：实际使用说明

### 1. 让任务经验形成 Skill

1. 在“技能 → 经验技能”开启“自动积累执行经验”。
2. 多次完成相似任务，至少产生 3 个独立成功证据。成功证据来自真实工具事件，不来自模型自述。
3. 等待空闲整理，或点右上角刷新立即加入整理队列。
4. 在“能力候选”中打开候选，检查适用场景、执行步骤、工具和每个来源。
5. 需要时直接修改 Skill 正文；同类候选可以用“合并重复候选”汇总来源。
6. 确认后点“验证并启用”。启用后该 Skill 才会进入可调用范围。
7. 后续可暂停、拒绝、恢复历史版本；新版本发布需要重新确认。

适合形成 Skill 的例子：固定格式周报、稳定的资料核对流程、重复出现的文件整理步骤。一次性的临时任务、未经验证的猜测和失败操作不应成为正式 Skill。

### 2. 创建自动任务

入口：设置 → 任务中心 → 右上角“+”。

1. 填写任务名称、目标与输出要求。
2. 需要项目记忆时选择项目；不需要时保持“全局”。
3. 选择时间计划或事件触发。
4. 添加执行步骤，并为 Agent/Skill 步骤写清输入、输出、工具边界和失败处理。
5. 条件分支用“不满足时跳过后续步骤数”控制流程。
6. 保存后可在任务卡片暂停、恢复、立即运行、复制或查看最近运行。
7. 开启“实时监听”以运行事件和目录监听；关闭时仍由系统周期检查。

每周任务至少要选择一个执行日。任务定义保存不等于已经执行；以运行详情和触发日志为准。

### 3. 文件触发任务案例：新 Excel 自动处理

1. 创建任务，关闭“时间计划”。
2. 事件选择“目录新文件”，选择一个通过 SAF 授权的目录，扩展名填写 `xlsx`。
3. 第一个 Agent 步骤写：“读取触发文件，检查工作表和关键字段，输出结构化摘要与异常清单。”
4. 第二个条件步骤设置最低电量或网络条件。
5. 第三个 Agent 步骤写：“根据前一步摘要生成整理结果并保存到指定项目目录。”
6. 最后添加通知步骤，通知正文留空时会使用前一步结果。
7. 保存并启用实时监听。首次扫描只建立基线，不会把目录里已有文件全部重跑。

### 4. 时间任务案例：每天 8 点行业报告

1. 创建任务并开启时间计划，周期选“每天”，小时 8、分钟 0。
2. 第一步 Agent 搜索公开资料并标注来源。
3. 第二步 Agent 只使用前一步结果整理报告、去重和标注不确定性，并保存到项目目录。
4. 第三步通知用户完成；失败策略选择停止流程。
5. 如设备受 Doze 影响，不要承诺正好 8:00 执行；应用会根据系统允许的时间补执行或等待网络/电量条件。

### 5. 临屏使用

1. 确认无障碍或 Root 虚拟屏后端可用。
2. 让 Agent 执行需要屏幕观察的任务，或在运行卡进入接管/临屏界面。
3. Agent 通过重新观察获取节点树和截图，再执行点击、输入、滑动或等待。
4. 用户手动操作后，Agent 会丢弃旧观察，下一步必须先重新观察。
5. 虚拟屏创建失败时会明确失败，不会静默操作手机主屏。
6. Root、焦点、输入法和 ColorOS 后台限制导致的失败需要在真机日志中确认；不能用模拟器结果代替。

### 6. 管理提示词与工具权限

1. 入口：技能 → 提示词与工具边界。
2. “附加行为提示”只影响表达和工作习惯，不能覆盖设备安全合同。
3. 先用能力组做粗粒度控制，再用“单项工具”排除个别工具。
4. 高风险能力组建议在不需要时关闭：终端与 Root、写入与删除文件、设备设置、敏感读取、MCP 外部工具、子 Agent。
5. 自动任务还可以设置任务级工具范围；最终权限是全局、任务、Skill 和子 Agent 的交集。

## 验证、限制与真机边界

本轮最终验证：

```text
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --console=plain
BUILD SUCCESSFUL
```

- 单元测试结果：419 个套件、2831 项，149 项平台跳过，0 失败、0 错误；实际执行通过 2682 项。跳过项不计作通过。
- Lint：0 错误、527 警告、2 提示。没有把已有警告宣称为零。
- `git diff --check` 通过；只有现有行尾转换提示，没有空白错误。
- 专项测试通过：自动任务流程、任务迁移、文件触发、Skill 演化、边界策略、临屏映射、临屏并发、学习迁移和 UI 截图。
- UI 截图位于 `app/build/reports/memory-learning-ui/`，已检查 320dp、深色、1.3 倍字体和 411dp 页面。

本次桌面交付产物：

- APK：`[本地交付目录]/Eta-v4.8.0-core-intelligence-debug.apk`
- 原始构建产物：`项目根目录\app\build\outputs\apk\debug\Eta-v4.8.0-debug.apk`
- 大小：33,444,209 字节
- SHA-256：`F12EC919399348A15057C54D493E22CC507AEB56A0C81EB78A17AD6FCCA4025F`
- 包名：`fuck.andes`；版本码：`2026110710`；APK 构建变体版本名：`4.8.0-debug`
- 签名与对齐：APK Signature Scheme v2 验证通过；`zipalign -c -v 4` 验证成功
- 安装状态：校验时 ADB 只检测到模拟器，没有检测到 OPPO 真机，因此未安装或启动；桌面 APK 保留，真机连接后再同步。

仍需遵守的能力边界：

- 自动 Skill 默认需要用户审阅后发布，不宣称可以无人审定直接固化能力。
- Skill 文件安装和学习数据库不是同一个数据库事务，依靠恢复日志协调；进程在极端时点被杀仍需要人工确认恢复状态。
- 工作流进程重启后不会从中间步骤继续；有界流程总超时 540 秒，单步等待最多 300 秒。
- 文件 Provider 若不提供有效的修改时间/大小，文件更新识别会受限；新文件触发不受此限制。
- Android WorkManager/AlarmManager 受 Doze、厂商后台策略、用户强停和应用被系统限制影响，不保证精确到分钟，也不保证强停后仍执行。
- Android 15/16/17 的 Robolectric 回归不是 OPPO/ColorOS 真机认证；Android 17/ColorOS 17 必须继续做 OTA 后真机验证。
- 当前临屏是选择性吸收参考设计，不是参考项目完整模块移植；Root display、输入法、多屏和焦点行为没有跨 ROM 保证。
- 工具边界能阻止应用层误调用，但不是系统沙箱；授予 Root/终端后仍存在等价系统操作能力。

这些限制需要在后续真机测试中逐项确认，不能用自动任务配置存在、Skill 候选生成或 UI 可点击来替代实际执行成功。
